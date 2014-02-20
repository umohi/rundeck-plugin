package org.jenkinsci.plugins.rundeck;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Run.Artifact;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.rundeck.api.*;
import org.rundeck.api.RundeckApiException.RundeckApiLoginException;
import org.rundeck.api.domain.RundeckExecution;
import org.rundeck.api.domain.RundeckExecution.ExecutionStatus;
import org.rundeck.api.domain.RundeckJob;

/**
 * Jenkins {@link Notifier} that runs a job on Rundeck (via the {@link RundeckClient})
 * 
 * @author Vincent Behar
 */
public class RundeckNotifier extends Notifier {

    /** Pattern used for the token expansion of $ARTIFACT_NAME{regex} */
    private static final transient Pattern TOKEN_ARTIFACT_NAME_PATTERN = Pattern.compile("\\$ARTIFACT_NAME\\{(.+)\\}");

    /** Pattern used for extracting the job reference (project:group/name) */
    private static final transient Pattern JOB_REFERENCE_PATTERN = Pattern.compile("^([^:]+?):(.*?)\\/?([^/]+)$");

    private final String jobId;

    private final String options;

    private final String nodeFilters;

    private final String tag;

    private final Boolean shouldWaitForRundeckJob;

    private final Boolean shouldFailTheBuild;

    @DataBoundConstructor
    public RundeckNotifier(String jobId, String options, String nodeFilters, String tag,
            Boolean shouldWaitForRundeckJob, Boolean shouldFailTheBuild) {
        this.jobId = jobId;
        this.options = options;
        this.nodeFilters = nodeFilters;
        this.tag = tag;
        this.shouldWaitForRundeckJob = shouldWaitForRundeckJob;
        this.shouldFailTheBuild = shouldFailTheBuild;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        if (build.getResult() != Result.SUCCESS) {
            return true;
        }

        RundeckClient rundeck = getSiteForProject(build.getProject());

        if (rundeck == null) {
            listener.getLogger().println("Rundeck configuration is not valid !");
            return false;
        }
        try {
            rundeck.ping();
        } catch (RundeckApiException e) {
            listener.getLogger().println("Rundeck is not running !");
            return false;
        }

        if (shouldNotifyRundeck(build, listener)) {
            return notifyRundeck(rundeck, build, listener);
        }

        return true;
    }
    
    public RundeckClient getSiteForProject(AbstractProject<?, ?> project) {
    	RundeckSite site = RundeckSite.get(project);
        return getRundeckClientBuilderFromSite(site);
    }

    public RundeckClient getRundeckClientBuilderFromSite(RundeckSite site) {
    	try {
            RundeckClientBuilder builder = RundeckClient.builder();
            builder.url(site.getUrl().toExternalForm());
            if (site.getToken() != null && !"".equals(site.getToken())) {
                builder.token(site.getToken());
            } else {
                builder.login(site.getLogin(), site.getPassword());
            }

            if (site.getVersion() != null && site.getVersion() > 0) {
                builder.version(site.getVersion());
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    /**
     * Check if we need to notify Rundeck for this build. If we have a tag, we will look for it in the changelog of the
     * build and in the changelog of all upstream builds.
     * 
     * @param build for checking the changelog
     * @param listener for logging the result
     * @return true if we should notify Rundeck, false otherwise
     */
    private boolean shouldNotifyRundeck(AbstractBuild<?, ?> build, BuildListener listener) {
        if (StringUtils.isBlank(tag)) {
            listener.getLogger().println("Notifying Rundeck...");
            return true;
        }

        // check for the tag in the changelog
        for (Entry changeLog : build.getChangeSet()) {
            if (StringUtils.containsIgnoreCase(changeLog.getMsg(), tag)) {
                listener.getLogger().println("Found " + tag + " in changelog (from " + changeLog.getAuthor().getId()
                                             + ") - Notifying Rundeck...");
                return true;
            }
        }

        // if we have an upstream cause, check for the tag in the changelog from upstream
        for (Cause cause : build.getCauses()) {
            if (UpstreamCause.class.isInstance(cause)) {
                UpstreamCause upstreamCause = (UpstreamCause) cause;
                TopLevelItem item = Hudson.getInstance().getItem(upstreamCause.getUpstreamProject());
                if (AbstractProject.class.isInstance(item)) {
                    AbstractProject<?, ?> upstreamProject = (AbstractProject<?, ?>) item;
                    AbstractBuild<?, ?> upstreamBuild = upstreamProject.getBuildByNumber(upstreamCause.getUpstreamBuild());
                    if (upstreamBuild != null) {
                        for (Entry changeLog : upstreamBuild.getChangeSet()) {
                            if (StringUtils.containsIgnoreCase(changeLog.getMsg(), tag)) {
                                listener.getLogger().println("Found " + tag + " in changelog (from "
                                                             + changeLog.getAuthor().getId() + ") in upstream build ("
                                                             + upstreamBuild.getFullDisplayName()
                                                             + ") - Notifying Rundeck...");
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Notify Rundeck : run a job on Rundeck
     * 
     * @param rundeck instance to notify
     * @param build for adding actions
     * @param listener for logging the result
     * @return true if successful, false otherwise
     */
    private boolean notifyRundeck(RundeckClient rundeck, AbstractBuild<?, ?> build, BuildListener listener) {
        //if the jobId is in the form "project:[group/*]name", find the actual job ID first.
        String foundJobId = null;
        try {
            foundJobId = RundeckDescriptor.findJobId(jobId, rundeck);
        } catch (RundeckApiException e) {
            listener.getLogger().println("Failed to get job with the identifier : " + jobId + " : "+e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            listener.getLogger().println("Failed to get job with the identifier : " + jobId + " : " +e.getMessage());
            return false;
        }
        if (foundJobId == null) {
            listener.getLogger().println("Could not find a job with the identifier : " + jobId);
            return false;
        }
        try {
            RundeckExecution execution = rundeck.triggerJob(RunJobBuilder.builder()
                    .setJobId(foundJobId)
                    .setOptions(parseProperties(options, build, listener))
                    .setNodeFilters(parseProperties(nodeFilters, build, listener))
                    .build());

            listener.getLogger().println("Notification succeeded ! Execution #" + execution.getId() + ", at "
                    + execution.getUrl() + " (status : " + execution.getStatus() + ")");
            build.addAction(new RundeckExecutionBuildBadgeAction(execution.getUrl()));

            if (Boolean.TRUE.equals(shouldWaitForRundeckJob)) {
                listener.getLogger().println("Waiting for Rundeck execution to finish...");
                while (ExecutionStatus.RUNNING.equals(execution.getStatus())) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        listener.getLogger().println("Oops, interrupted ! " + e.getMessage());
                        break;
                    }
                    execution = rundeck.getExecution(execution.getId());
                }
                listener.getLogger().println("Rundeck execution #" + execution.getId() + " finished in "
                        + execution.getDuration() + ", with status : " + execution.getStatus());

                switch (execution.getStatus()) {
                    case SUCCEEDED:
                        return true;
                    case ABORTED:
                    case FAILED:
                        return false;
                    default:
                        return true;
                }
            } else {
                return true;
            }
        } catch (RundeckApiLoginException e) {
            listener.getLogger().println("Login failed on " + rundeck.getUrl() + " : " + e.getMessage());
            return false;
        } catch (RundeckApiException.RundeckApiTokenException e) {
            listener.getLogger().println("Token auth failed on " + rundeck.getUrl() + " : " + e.getMessage());
            return false;
        } catch (RundeckApiException e) {
            listener.getLogger().println("Error while talking to Rundeck's API at " + rundeck.getUrl() + " : "
                                         + e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            listener.getLogger().println("Configuration error : " + e.getMessage());
            return false;
        }
    }

    /**
     * Parse the given input (should be in the Java-Properties syntax) and expand Jenkins environment variables.
     * 
     * @param input specified in the Java-Properties syntax (multi-line, key and value separated by = or :)
     * @param build for retrieving Jenkins environment variables
     * @param listener for retrieving Jenkins environment variables and logging the errors
     * @return A {@link Properties} instance (may be empty), or null if unable to parse the options
     */
    private Properties parseProperties(String input, AbstractBuild<?, ?> build, BuildListener listener) {
        if (StringUtils.isBlank(input)) {
            return new Properties();
        }

        // try to expand jenkins env vars
        try {
            EnvVars envVars = build.getEnvironment(listener);
            input = Util.replaceMacro(input, envVars);
        } catch (Exception e) {
            listener.getLogger().println("Failed to expand environment variables : " + e.getMessage());
        }

        // expand our custom tokens : $ARTIFACT_NAME{regex} => name of the first matching artifact found
        // http://groups.google.com/group/rundeck-discuss/browse_thread/thread/94a6833b84fdc10b
        Matcher matcher = TOKEN_ARTIFACT_NAME_PATTERN.matcher(input);
        int idx = 0;
        while (matcher.find(idx)) {
            idx = matcher.end();
            String regex = matcher.group(1);
            Pattern pattern = Pattern.compile(regex);
            for (@SuppressWarnings("rawtypes")
            Artifact artifact : build.getArtifacts()) {
                if (pattern.matcher(artifact.getFileName()).matches()) {
                    input = StringUtils.replace(input, matcher.group(0), artifact.getFileName());
                    idx = matcher.start() + artifact.getFileName().length();
                    break;
                }
            }
        }

        try {
            return Util.loadProperties(input);
        } catch (IOException e) {
            listener.getLogger().println("Failed to parse : " + input);
            listener.getLogger().println("Error : " + e.getMessage());
            return null;
        }
    }

    @Override
    public Action getProjectAction(AbstractProject<?, ?> project) {
        try {
        	//FIXME: verify logic
            return new RundeckJobProjectLinkerAction(getDescriptor().getRundeckInstance(project.getLastBuild()), jobId);
        } catch (RundeckApiException e) {
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * If we should not fail the build, we need to run after finalized, so that the result of "perform" is not used by
     * Jenkins
     */
    @Override
    public boolean needsToRunAfterFinalized() {
        return !shouldFailTheBuild;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public String getJobIdentifier() {
        return jobId;
    }

    public String getJobId() {
        return jobId;
    }

    public String getOptions() {
        return options;
    }

    public String getNodeFilters() {
        return nodeFilters;
    }

    public String getTag() {
        return tag;
    }

    public Boolean getShouldWaitForRundeckJob() {
        return shouldWaitForRundeckJob;
    }

    public Boolean getShouldFailTheBuild() {
        return shouldFailTheBuild;
    }

    @Override
    public RundeckDescriptor getDescriptor() {
        return (RundeckDescriptor) super.getDescriptor();
    }

    @Extension(ordinal = 1000)
    public static final class RundeckDescriptor extends BuildStepDescriptor<Publisher> {

        private RundeckClient rundeckInstance;

        public RundeckDescriptor() {
            super();
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            save();
            return super.configure(req, json);
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
        	RundeckProjectProperty jpp = req.bindParameters(RundeckProjectProperty.class, "rundeck.");
            if (jpp.siteName == null) {
            	throw new FormException("Rundeck global config not valid", "jobIdentifier");
            }
            
            String jobIdentifier = formData.getString("jobIdentifier");
            RundeckSite site = jpp.getSite();
            RundeckClient client = new RundeckNotifier(jobIdentifier,
                    formData.getString("options"),
                    formData.getString("nodeFilters"),
                    formData.getString("tag"),
                    formData.getBoolean("shouldWaitForRundeckJob"),
                    formData.getBoolean("shouldFailTheBuild")).getRundeckClientBuilderFromSite(site);
            RundeckJob job = null;
            try {
            	rundeckInstance = client;
                job = findJob(jobIdentifier, rundeckInstance);
            } catch (RundeckApiException e) {
                throw new FormException("Failed to get job with the identifier : " + jobIdentifier, e, "jobIdentifier");
            } catch (IllegalArgumentException e) {
                throw new FormException("Failed to get job with the identifier : " + jobIdentifier, e, "jobIdentifier");
            }
            if (job == null) {
                throw new FormException("Could not found a job with the identifier : " + jobIdentifier, "jobIdentifier");
            }
            return new RundeckNotifier(jobIdentifier,
                                       formData.getString("options"),
                                       formData.getString("nodeFilters"),
                                       formData.getString("tag"),
                                       formData.getBoolean("shouldWaitForRundeckJob"),
                                       formData.getBoolean("shouldFailTheBuild"));
        }

        

        public FormValidation doCheckJobIdentifier(@QueryParameter("jobIdentifier") final String jobIdentifier) {
            if (rundeckInstance == null) {
                return FormValidation.error("Rundeck global configuration is not valid !");
            }
            if (StringUtils.isBlank(jobIdentifier)) {
                return FormValidation.error("The job identifier is mandatory !");
            }
            try {
                RundeckJob job = findJob(jobIdentifier, rundeckInstance);
                if (job == null) {
                    return FormValidation.error("Could not find a job with the identifier : %s", jobIdentifier);
                } else {
                    return FormValidation.ok("Your Rundeck job is : %s [%s] %s",
                                             job.getId(),
                                             job.getProject(),
                                             job.getFullName());
                }
            } catch (RundeckApiException e) {
                return FormValidation.error("Failed to get job details : %s", e.getMessage());
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Failed to get job details : %s", e.getMessage());
            }
        }

        /**
         * Return a rundeck Job ID, by find a rundeck job if the identifier is a project:[group/]*name format, otherwise
         * returning the original identifier as the ID.
         * @param jobIdentifier either a Job ID, or "project:[group/]*name"
         * @param rundeckClient the client instance
         * @return a job UUID
         * @throws RundeckApiException
         * @throws IllegalArgumentException
         */
        static String findJobId(String jobIdentifier, RundeckClient rundeckClient) throws RundeckApiException,
                IllegalArgumentException {
            Matcher matcher = JOB_REFERENCE_PATTERN.matcher(jobIdentifier);
            if (matcher.find() && matcher.groupCount() == 3) {
                String project = matcher.group(1);
                String groupPath = matcher.group(2);
                String name = matcher.group(3);
                return rundeckClient.findJob(project, groupPath, name).getId();
            } else {
                return jobIdentifier;
            }
        }
        /**
         * Find a {@link RundeckJob} with the given identifier
         *
         * @param jobIdentifier either a simple ID, an UUID or a reference (project:group/name)
         * @param rundeckInstance
         * @return the {@link RundeckJob} found, or null if not found
         * @throws RundeckApiException in case of error, or if no job with this ID
         * @throws IllegalArgumentException if the identifier is not valid
         */
        public static RundeckJob findJob(String jobIdentifier, RundeckClient rundeckInstance) throws RundeckApiException, IllegalArgumentException {
            Matcher matcher = JOB_REFERENCE_PATTERN.matcher(jobIdentifier);
            if (matcher.find() && matcher.groupCount() == 3) {
                String project = matcher.group(1);
                String groupPath = matcher.group(2);
                String name = matcher.group(3);
                return rundeckInstance.findJob(project, groupPath, name);
            } else {
                return rundeckInstance.getJob(jobIdentifier);
            }
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Rundeck";
        }

        public RundeckClient getRundeckInstance(AbstractBuild<?, ?> build) {
        	RundeckSite site = RundeckSite.get(build.getProject());
            if (site == null) {
                throw new IllegalStateException("Rundeck site needs to be configured in the project " + build.getFullDisplayName());
            }
            
            try {
                RundeckClientBuilder builder = RundeckClient.builder();
                builder.url(site.getUrl().toExternalForm());
                if (site.getToken() != null && !"".equals(site.getToken())) {
                    builder.token(site.getToken());
                } else {
                    builder.login(site.getLogin(), site.getPassword());
                }

                if (site.getVersion() != null && site.getVersion() > 0) {
                    builder.version(site.getVersion());
                }
                rundeckInstance=builder.build();
            } catch (IllegalArgumentException e) {
                rundeckInstance = null;
            }
            
            return rundeckInstance;
        }

        public void setRundeckInstance(RundeckClient rundeckInstance) {
            this.rundeckInstance = rundeckInstance;
        }
    }

    /**
     * {@link BuildBadgeAction} used to display a Rundeck icon + a link to the Rundeck execution page, on the Jenkins
     * build history and build result page.
     */
    public static class RundeckExecutionBuildBadgeAction implements BuildBadgeAction {

        private final String executionUrl;

        public RundeckExecutionBuildBadgeAction(String executionUrl) {
            super();
            this.executionUrl = executionUrl;
        }

        public String getDisplayName() {
            return "Rundeck Execution Result";
        }

        public String getIconFileName() {
            return "/plugin/rundeck/images/rundeck_24x24.png";
        }

        public String getUrlName() {
            return executionUrl;
        }

    }

}
