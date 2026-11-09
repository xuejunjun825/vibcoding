/**
 * 安全 Markdown 渲染（DESIGN §6 `MarkdownView`）。
 *
 * 铁律：**不解析原始 HTML**。输入先整体转义，再只对「本文件认识的那几种标记」做替换，
 * 所以 `<script>`、`<img onerror>`、`<iframe>` 都只会以文本出现 —— 比服务端白名单更严，
 * 不需要引入 DOMPurify 之类的依赖。任何新增语法都必须在这套「先转义后拼装」的路径里实现，
 * 不允许直接把字符串塞进 innerHTML。
 *
 * 支持：标题 h1–h3、段落、粗体/斜体/删除线/行内代码、围栏代码块、有序/无序列表、
 * 引用、分隔线、表格、链接、图片、Wiki 内链 `[[pageId|标题]]`。
 */

export function escapeHtml(source: string): string {
  return String(source ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 只放行 http(s)/mailto/站内相对路径；`javascript:`、`data:` 一律丢弃 */
function safeUrl(raw: string): string {
  const url = raw.trim()
  if (!url) return ''
  if (/^(https?:\/\/|mailto:|\/|#)/i.test(url)) return url
  if (/^[\w.-]+(\/|$)/.test(url)) return `https://${url}`
  return ''
}

export interface MarkdownHeading {
  level: number
  text: string
  id: string
}

interface RenderState {
  headings: MarkdownHeading[]
}

function headingId(text: string, index: number): string {
  const slug = text
    .toLowerCase()
    .replace(/[^\w\u4e00-\u9fa5]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return `h-${index}-${slug || 'section'}`
}

function renderInline(raw: string): string {
  let text = escapeHtml(raw)

  // 行内代码先占位，避免里面的 `*` `_` 被当成强调
  const codes: string[] = []
  text = text.replace(/`([^`]+)`/g, (_all, code: string) => {
    codes.push(code)
    return `\u0000code${codes.length - 1}\u0000`
  })

  // Wiki 内链 [[12|标题]] / [[12]]
  text = text.replace(/\[\[(\d+)(?:\|([^\]]+))?\]\]/g, (_all, id: string, label?: string) => {
    const text2 = label ? label : `#${id}`
    return `<a href="/wiki?page=${id}" data-wiki-page="${id}" class="md-link md-link--wiki">${text2}</a>`
  })

  // 图片 ![alt](url) —— 只允许安全 URL
  text = text.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_all, alt: string, url: string) => {
    const safe = safeUrl(url)
    if (!safe) return escapeHtml(alt)
    return `<img class="md-img" src="${escapeHtml(safe)}" alt="${alt}" loading="lazy" />`
  })

  // 链接 [text](url)
  text = text.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_all, label: string, url: string) => {
    const safe = safeUrl(url)
    if (!safe) return label
    const external = /^https?:\/\//i.test(safe)
    const attrs = external ? ' target="_blank" rel="noopener noreferrer"' : ''
    return `<a class="md-link" href="${escapeHtml(safe)}"${attrs}>${label}</a>`
  })

  text = text
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
    .replace(/~~([^~]+)~~/g, '<del>$1</del>')

  text = text.replace(/\u0000code(\d+)\u0000/g, (_all, index: string) => {
    const code = codes[Number(index)] ?? ''
    return `<code class="md-code">${code}</code>`
  })

  return text
}

function splitRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

function isTableDivider(line: string): boolean {
  return /^\s*\|?[\s:-]*-[\s|:-]*\|?\s*$/.test(line) && line.includes('-') && line.includes('|')
}

/**
 * 渲染整篇 Markdown。返回的 HTML 只由本函数拼接，所有用户文本均已转义。
 * `headings` 通过第二个参数回传，供 TOC 使用（避免再解析一遍）。
 */
export function renderMarkdown(source: string, headings: MarkdownHeading[] = []): string {
  // 复制一份：调用方常把同一个 headings 数组既喂给目录又喂给渲染，
  // 原地 push 会让目录条目翻倍（页内锚点重复）。
  const state: RenderState = { headings: [...headings] }
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n')
  const out: string[] = []
  let index = 0
  let headingIndex = 0

  const flushParagraph = (buffer: string[]): void => {
    if (!buffer.length) return
    out.push(`<p class="md-p">${buffer.map(renderInline).join('<br />')}</p>`)
    buffer.length = 0
  }

  const paragraph: string[] = []

  while (index < lines.length) {
    const line = lines[index]

    // 围栏代码块
    const fence = /^\s*```(\w*)\s*$/.exec(line)
    if (fence) {
      flushParagraph(paragraph)
      const lang = fence[1] || ''
      const body: string[] = []
      index += 1
      while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) {
        body.push(lines[index])
        index += 1
      }
      index += 1
      out.push(
        `<pre class="md-pre"${lang ? ` data-lang="${escapeHtml(lang)}"` : ''}><code>${escapeHtml(
          body.join('\n'),
        )}</code></pre>`,
      )
      continue
    }

    if (/^\s*$/.test(line)) {
      flushParagraph(paragraph)
      index += 1
      continue
    }

    const heading = /^(#{1,3})\s+(.*)$/.exec(line)
    if (heading) {
      flushParagraph(paragraph)
      const level = heading[1].length
      const text = heading[2].trim()
      const id = headingId(text, headingIndex)
      headingIndex += 1
      state.headings.push({ level, text, id })
      out.push(`<h${level} id="${id}" class="md-h md-h${level}">${renderInline(text)}</h${level}>`)
      index += 1
      continue
    }

    if (/^\s*(---|\*\*\*|___)\s*$/.test(line)) {
      flushParagraph(paragraph)
      out.push('<hr class="md-hr" />')
      index += 1
      continue
    }

    // 表格
    if (line.includes('|') && index + 1 < lines.length && isTableDivider(lines[index + 1])) {
      flushParagraph(paragraph)
      const header = splitRow(line)
      index += 2
      const rows: string[][] = []
      while (index < lines.length && lines[index].includes('|') && !/^\s*$/.test(lines[index])) {
        rows.push(splitRow(lines[index]))
        index += 1
      }
      const head = header.map((cell) => `<th>${renderInline(cell)}</th>`).join('')
      const body = rows
        .map((row) => `<tr>${row.map((cell) => `<td>${renderInline(cell)}</td>`).join('')}</tr>`)
        .join('')
      out.push(
        `<div class="md-table-wrap"><table class="md-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`,
      )
      continue
    }

    // 引用（连续多行合成一个 blockquote）
    if (/^\s*>\s?/.test(line)) {
      flushParagraph(paragraph)
      const quote: string[] = []
      while (index < lines.length && /^\s*>\s?/.test(lines[index])) {
        quote.push(lines[index].replace(/^\s*>\s?/, ''))
        index += 1
      }
      out.push(`<blockquote class="md-quote">${quote.map(renderInline).join('<br />')}</blockquote>`)
      continue
    }

    // 无序列表
    if (/^\s*[-*+]\s+/.test(line)) {
      flushParagraph(paragraph)
      const items: string[] = []
      while (index < lines.length && /^\s*[-*+]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*[-*+]\s+/, ''))
        index += 1
      }
      out.push(`<ul class="md-ul">${items.map((item) => `<li>${renderInline(item)}</li>`).join('')}</ul>`)
      continue
    }

    // 有序列表
    if (/^\s*\d+[.)]\s+/.test(line)) {
      flushParagraph(paragraph)
      const items: string[] = []
      while (index < lines.length && /^\s*\d+[.)]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*\d+[.)]\s+/, ''))
        index += 1
      }
      out.push(`<ol class="md-ol">${items.map((item) => `<li>${renderInline(item)}</li>`).join('')}</ol>`)
      continue
    }

    paragraph.push(line)
    index += 1
  }

  flushParagraph(paragraph)
  return out.join('\n')
}

/** 只抽标题（TOC 用），不产生 HTML */
export function extractHeadings(source: string): MarkdownHeading[] {
  const headings: MarkdownHeading[] = []
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n')
  let inFence = false
  let index = 0
  for (const line of lines) {
    if (/^\s*```/.test(line)) {
      inFence = !inFence
      continue
    }
    if (inFence) continue
    const heading = /^(#{1,3})\s+(.*)$/.exec(line)
    if (!heading) continue
    const text = heading[2].trim()
    headings.push({ level: heading[1].length, text, id: headingId(text, index) })
    index += 1
  }
  return headings
}

/** 纯文本摘要（搜索片段兜底 / 预览用），不产生 HTML */
export function toPlainText(source: string): string {
  return String(source ?? '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/\|[^\n]*\|/g, ' ')
    .replace(/[#>*_`~\-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}
