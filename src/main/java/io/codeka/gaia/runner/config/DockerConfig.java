package io.codeka.gaia.runner.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import io.codeka.gaia.bo.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration of the docker client
 */
@Configuration
public class DockerConfig {

    /**
     * builds the docker client
     * @param settings the Gaia's settings
     * @return a docker client
     */
    @Bean
    DockerClient client(Settings settings) {
        return DockerClientBuilder.getInstance()
                .build();
    }

    @Bean
    ContainerConfig containerConfig(){
        return new ContainerConfig()
                .withImage("hashicorp/terraform:0.11.14")
                // bind mounting the docker sock (to be able to use docker provider in terraform)
                // .withHostConfig(HostConfig.builder().binds(HostConfig.Bind.builder().from("/var/run/docker.sock").to("/var/run/docker.sock").build()).build())
                // resetting entrypoint to empty
                .withEntrypoint(null)
                // and using a simple shell as command
                .withCmd(new String[]{"/bin/sh"})
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withStdInOnce(true)
                .withStdinOpen(true)
                .withTty(false);
    }

}
