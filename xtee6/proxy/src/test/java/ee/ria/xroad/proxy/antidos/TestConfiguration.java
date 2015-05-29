package ee.ria.xroad.proxy.antidos;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
class TestConfiguration extends AntiDosConfiguration {
    private final int minFreeFileHandles;
    private final double maxCpuLoad;
}