package com.example.req.collab.gantt;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 甘特的两个入口：读看板 / 改一期。改期只有一个写入口，业务规则全在
 * {@link GanttRescheduleService}（controller 只做参数绑定）。
 */
@RestController
@RequestMapping("/gantt")
public class GanttController {

    private final GanttService ganttService;
    private final GanttRescheduleService rescheduleService;

    public GanttController(GanttService ganttService, GanttRescheduleService rescheduleService) {
        this.ganttService = ganttService;
        this.rescheduleService = rescheduleService;
    }

    @GetMapping("/rows")
    public GanttViews.Board rows(@RequestParam(required = false) String view,
                                 @RequestParam(required = false) String zoom,
                                 @RequestParam(required = false) Long domainId,
                                 @RequestParam(required = false) Long vendorId,
                                 @RequestParam(required = false) Long releaseId,
                                 @RequestParam(required = false) Long sprintId,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String expanded,
                                 @RequestParam(required = false) Integer limit) {
        return ganttService.board(new GanttViews.Query(view, zoom, domainId, vendorId, releaseId, sprintId,
                from, to, expanded, limit));
    }

    @PatchMapping("/rows/{type}/{id}")
    public GanttViews.RescheduleResult reschedule(@PathVariable String type,
                                                  @PathVariable long id,
                                                  @RequestBody(required = false) GanttViews.RescheduleCmd body) {
        return rescheduleService.reschedule(type, id, body);
    }
}
