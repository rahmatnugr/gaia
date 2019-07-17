package io.codeka.gaia.runner;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.codeka.gaia.bo.*;
import io.codeka.gaia.repository.JobRepository;
import io.codeka.gaia.repository.StackRepository;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.ReaderInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs a module instance
 */
@Service
public class StackRunner {

    private DockerClient dockerClient;

    private ContainerConfig containerConfig;

    private Settings settings;

    private StackCommandBuilder stackCommandBuilder;

    private Map<String, Job> jobs = new HashMap<>();

    private StackRepository stackRepository;

    private HttpHijackWorkaround httpHijackWorkaround;

    private JobRepository jobRepository;

    @Autowired
    public StackRunner(DockerClient dockerClient, ContainerConfig containerConfig, Settings settings, StackCommandBuilder stackCommandBuilder, StackRepository stackRepository, HttpHijackWorkaround httpHijackWorkaround, JobRepository jobRepository) {
        this.dockerClient = dockerClient;
        this.containerConfig = containerConfig;
        this.settings = settings;
        this.stackCommandBuilder = stackCommandBuilder;
        this.stackRepository = stackRepository;
        this.httpHijackWorkaround = httpHijackWorkaround;
        this.jobRepository = jobRepository;
    }

    private int runContainerForJob(Job job, String script){
        try{
            // FIXME This is certainly no thread safe !
            var containerConfig = this.containerConfig
                    .withEnv(settings.env().toArray(new String[]{}));

            // pull the image
            dockerClient.pullImageCmd("hashicorp/terraform:0.11.14")
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();

            System.out.println("Create container");
            var containerCreation = dockerClient.createContainerCmd("hashicorp/terraform:0.11.14")
                    .withEntrypoint()
                    // and using a simple shell as command
                    .withCmd(new String[]{"/bin/sh"})
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withStdInOnce(true)
                    .withStdinOpen(true)
                    .withTty(false)
                    .withEnv(settings.env().toArray(new String[]{}));

            var containerId = containerCreation.exec().getId();

            //DockerClient.AttachParameter.STDIN, DockerClient.AttachParameter.STDOUT, DockerClient.AttachParameter.STDERR, DockerClient.AttachParameter.STREAM);
            //var writable = httpHijackWorkaround.getOutputStream(logStream, "unix:///var/apply/docker.sock");

            System.err.println("Starting container");
            //dockerClient.startContainer(containerId);
            dockerClient.startContainerCmd(containerId).exec();

            final PipedInputStream stdout = new PipedInputStream();
            final PipedInputStream stderr = new PipedInputStream();
            final PipedOutputStream stdout_pipe = new PipedOutputStream(stdout);
            final PipedOutputStream stderr_pipe = new PipedOutputStream(stderr);

            // writing to System.err
//            CompletableFuture.runAsync(() -> {
//                try {
//                    System.err.println("Copying to System.err");
//                    System.err.println("Attaching stdout and stderr");
//                    // apply another attachment for stdout and stderr (who knows why?)
//                    dockerClient.attachContainer(containerId,
//                            DockerClient.AttachParameter.LOGS, DockerClient.AttachParameter.STDOUT,
//                            DockerClient.AttachParameter.STDERR, DockerClient.AttachParameter.STREAM)
//                            .attach(stdout_pipe, stderr_pipe, true);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            });

            CompletableFuture.runAsync(() -> {
                try {
                    IOUtils.copy(stdout, job.getLogsWriter(), Charset.defaultCharset());
                    System.err.println("Copy done !");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            CompletableFuture.runAsync(() -> {
                try {
                    IOUtils.copy(stderr, job.getLogsWriter(), Charset.defaultCharset());
                    System.err.println("Copy done !");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            System.err.println("Writing buffer");
            StringReader reader = new StringReader(script);
            InputStream is = new ReaderInputStream(reader, Charset.defaultCharset());

            // attach stdin
            System.err.println("Attach container");
            var logStream = dockerClient.attachContainerCmd(containerId)
                    .withStdIn(is)
                    .exec(new AttachContainerResultCallback())
                    .awaitCompletion();

            // wait for the container to exit
            System.err.println("Waiting container exit");
            var containerExit = dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitStatusCode();

            dockerClient.removeContainerCmd(containerId).exec();

            return containerExit;
        } catch (Exception e) {
            e.printStackTrace();
            return 99;
        }
    }

    @Async
    public void apply(Job job, TerraformModule module, Stack stack) {
        this.jobs.put(job.getId(), job);
        job.start();

        var applyScript = stackCommandBuilder.buildApplyScript(stack, module);

        var result = runContainerForJob(job, applyScript);

        if(result == 0){
            job.end();

            // update stack information
            stack.setState(StackState.RUNNING);
            stackRepository.save(stack);
        }
        else{
            job.fail();
        }

        // save job to database
        jobRepository.save(job);
        this.jobs.remove(job.getId());
    }

    /**
     * Runs a "plan job".
     * A plan job runs a 'terraform plan'
     * @param job
     * @param module
     * @param stack
     */
    @Async
    public void plan(Job job, TerraformModule module, Stack stack) {
        this.jobs.put(job.getId(), job);
        job.start();

        var planScript = stackCommandBuilder.buildPlanScript(stack, module);

        var result = runContainerForJob(job, planScript);

        if(result == 0){
            // diff is empty
            job.end();
        }
        else if(result == 2){
            // there is a diff, set the status of the stack to : "TO_UPDATE"
            if(StackState.RUNNING != stack.getState() && StackState.NEW != stack.getState()){
                stack.setState(StackState.TO_UPDATE);
                stackRepository.save(stack);
            }
            job.end();
        }
        else{
            // error
            job.fail();
        }

        // save job to database
        jobRepository.save(job);
        this.jobs.remove(job.getId());
    }

    public Job getJob(String jobId) {
        if (this.jobs.containsKey(jobId)) {
            // try in memory
            return this.jobs.get(jobId);
        }
        // or find in repository
        return this.jobRepository.findById(jobId).get();
    }

    /**
     * Runs a "stop job".
     * A stop job runs a 'terraform destroy'
     * @param job
     * @param module
     * @param stack
     */
    @Async
    public void stop(Job job, TerraformModule module, Stack stack) {
        this.jobs.put(job.getId(), job);
        job.start();

        var destroyScript = stackCommandBuilder.buildDestroyScript(stack, module);

        var result = runContainerForJob(job, destroyScript);

        if(result == 0){
            job.end();
            // update state
            stack.setState(StackState.STOPPED);
            stackRepository.save(stack);
        } else{
            // error
            job.fail();
        }

        // save job to database
        jobRepository.save(job);
        this.jobs.remove(job.getId());
    }

}
