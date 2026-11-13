package io.typeforge.operations;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class FrameworkController {
    public record Health(String status, String scope) {}
    public record Module(String id, String name, String owner, String status) {}
    public record Framework(String phase, boolean businessReady, List<Module> modules) {}
    @GetMapping("/api/v1/health")
    public Health health() { return new Health("UP", "process-only; database/model/worker not connected"); }
    @GetMapping("/api/v1/framework")
    public Framework framework() {
        return new Framework("agent-workspace-baseline", false, List.of(
            new Module("M01", "身份、权限与偏好", "B", "planned"),
            new Module("M02", "知识地图与阅读", "A", "planned"),
            new Module("M03", "类型关系实验室", "B", "planned"),
            new Module("M04", "编译诊断与沙箱", "B", "planned"),
            new Module("M05", "练习与类型安全改造", "B", "planned"),
            new Module("M06", "学习证据与复习", "B", "planned"),
            new Module("M07", "数据集与知识加工", "A", "planned"),
            new Module("M08", "检索与证据", "A", "planned"),
            new Module("M09", "教学专家与证据核验", "A", "planned"),
            new Module("M10", "内容审核与发布", "A", "planned"),
            new Module("M11", "协作评测与答辩证据", "A+B", "planned"),
            new Module("M12", "运行与安全", "B", "planned"),
            new Module("M13", "动态专家编排与任务运行时", "A", "planned"),
            new Module("M14", "多模态学习创作工坊", "B", "planned"),
            new Module("M15", "学习工作空间与上下文记忆", "A", "planned"),
            new Module("M16", "高交互错题集与复练作品", "B", "planned")
        ));
    }
}
