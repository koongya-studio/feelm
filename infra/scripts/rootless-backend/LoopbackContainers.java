package ci222;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.core.CreateContainerCmdModifier;

/** Operator-reviewed compatibility pilot only; not a Docker authorization boundary. */
public final class LoopbackContainers implements CreateContainerCmdModifier {
    @Override
    public CreateContainerCmd modify(CreateContainerCmd command) {
        HostConfig host = command.getHostConfig();
        if (host == null) {
            throw new IllegalStateException("Missing Testcontainers host configuration");
        }
        String network = host.getNetworkMode();
        if (network != null && !network.equals("bridge") && !network.equals("default")) {
            throw new IllegalStateException("Unexpected test container network mode");
        }
        Ports ports = new Ports();
        ExposedPort[] exposed = command.getExposedPorts();
        if (exposed != null) {
            for (ExposedPort port : exposed) {
                ports.bind(port, Ports.Binding.bindIp("127.0.0.1"));
            }
        }
        host.withPortBindings(ports).withPublishAllPorts(false).withPrivileged(false)
                .withMemory(768L * 1024 * 1024).withNanoCPUs(1_000_000_000L).withPidsLimit(256L);
        System.out.println("CI222_CONTAINER_POLICY: loopback-only; privileged=false; bounded resources");
        return command;
    }
}
