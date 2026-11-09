package com.example.req.collab.wiki;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级 LCS diff 的唯一实现（TECHNICAL §3.2）。
 *
 * <p>服务端算、前端只渲染：{@code web/src/utils/diff.ts} 已按设计取消，避免同一份内容出现
 * 两套「哪些行变了」的口径。算法是经典 LCS，先剥掉公共前后缀把 DP 压到真正变化的区间；
 * 超大文本（>200 万格）退化为「整段删除 + 整段新增」，仍保持行号正确，不牺牲可用性。</p>
 *
 * <p>纯函数、无依赖，可冻结输入单测。</p>
 */
public final class WikiDiff {

    /** DP 单元上限：4 字节/格 × 200 万 ≈ 8MB，超过就退化为粗粒度结果。 */
    private static final long MAX_DP_CELLS = 2_000_000L;

    private WikiDiff() {
    }

    public static WikiViews.Diff compute(String oldText, String newText) {
        String[] oldLines = split(oldText);
        String[] newLines = split(newText);

        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length
                && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) {
            suffix++;
        }

        List<WikiViews.DiffLine> lines = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            lines.add(new WikiViews.DiffLine("SAME", i + 1, i + 1, oldLines[i]));
        }

        int oldFrom = prefix;
        int oldTo = oldLines.length - suffix;
        int newFrom = prefix;
        int newTo = newLines.length - suffix;
        long cells = (long) (oldTo - oldFrom) * (newTo - newFrom);
        if (cells > MAX_DP_CELLS) {
            for (int i = oldFrom; i < oldTo; i++) {
                lines.add(new WikiViews.DiffLine("DEL", i + 1, null, oldLines[i]));
            }
            for (int j = newFrom; j < newTo; j++) {
                lines.add(new WikiViews.DiffLine("ADD", null, j + 1, newLines[j]));
            }
        } else {
            lcs(oldLines, newLines, oldFrom, oldTo, newFrom, newTo, lines);
        }

        for (int i = 0; i < suffix; i++) {
            int oldNo = oldTo + i + 1;
            int newNo = newTo + i + 1;
            lines.add(new WikiViews.DiffLine("SAME", oldNo, newNo, oldLines[oldTo + i]));
        }

        int added = 0;
        int removed = 0;
        for (WikiViews.DiffLine line : lines) {
            if ("ADD".equals(line.kind())) {
                added++;
            } else if ("DEL".equals(line.kind())) {
                removed++;
            }
        }
        return new WikiViews.Diff(lines, added, removed, changed(lines));
    }

    /** 相邻的「删一段 + 增一段」视为改写；统计被改写的行数（取两段较小值）。 */
    private static int changed(List<WikiViews.DiffLine> lines) {
        int changed = 0;
        int i = 0;
        while (i < lines.size()) {
            if ("SAME".equals(lines.get(i).kind())) {
                i++;
                continue;
            }
            int del = 0;
            int add = 0;
            while (i < lines.size() && !"SAME".equals(lines.get(i).kind())) {
                if ("DEL".equals(lines.get(i).kind())) {
                    del++;
                } else {
                    add++;
                }
                i++;
            }
            changed += Math.min(del, add);
        }
        return changed;
    }

    private static void lcs(String[] oldLines, String[] newLines, int oldFrom, int oldTo,
                            int newFrom, int newTo, List<WikiViews.DiffLine> out) {
        int n = oldTo - oldFrom;
        int m = newTo - newFrom;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (oldLines[oldFrom + i].equals(newLines[newFrom + j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            String oldLine = oldLines[oldFrom + i];
            String newLine = newLines[newFrom + j];
            if (oldLine.equals(newLine)) {
                out.add(new WikiViews.DiffLine("SAME", oldFrom + i + 1, newFrom + j + 1, oldLine));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new WikiViews.DiffLine("DEL", oldFrom + i + 1, null, oldLine));
                i++;
            } else {
                out.add(new WikiViews.DiffLine("ADD", null, newFrom + j + 1, newLine));
                j++;
            }
        }
        while (i < n) {
            out.add(new WikiViews.DiffLine("DEL", oldFrom + i + 1, null, oldLines[oldFrom + i]));
            i++;
        }
        while (j < m) {
            out.add(new WikiViews.DiffLine("ADD", null, newFrom + j + 1, newLines[newFrom + j]));
            j++;
        }
    }

    private static String[] split(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.split("\n", -1);
    }
}
