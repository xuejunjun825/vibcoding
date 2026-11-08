package com.example.deliver.sprint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.example.deliver.task.TaskViews.TaskView;

public final class SprintViews {

    private SprintViews() {
    }

    public record CreateSprint(Long releaseId,
                               String name,
                               String goal,
                               LocalDate startDate,
                               LocalDate endDate,
                               BigDecimal capacityDays) {
    }

    public record SprintView(long id,
                             Long releaseId,
                             String name,
                             String goal,
                             LocalDate startDate,
                             LocalDate endDate,
                             BigDecimal capacityDays,
                             String status,
                             long taskCount,
                             long doneCount,
                             BigDecimal completionRate,
                             LocalDateTime updatedAt) {
    }

    /**
     * The board is grouped server-side so every client renders the same four columns in the same
     * order, and can never invent a fifth.
     */
    public record SprintBoard(SprintView sprint,
                              Map<String, List<TaskView>> columns,
                              List<String> columnOrder,
                              long openTasks) {
    }
}
