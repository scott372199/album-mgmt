package top.chenyouyi.album.mgmt.provider;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import top.chenyouyi.album.mgmt.provider.dto.ManagementSnapshot;

@RestController
@RequestMapping("/api/management")
public class ManagementReadController {
    private final ManagementReadAdapter adapter;

    public ManagementReadController(ManagementReadAdapter adapter) {
        this.adapter = adapter;
    }

    @GetMapping("/snapshot")
    public ManagementSnapshot snapshot() {
        return adapter.snapshot();
    }
}
