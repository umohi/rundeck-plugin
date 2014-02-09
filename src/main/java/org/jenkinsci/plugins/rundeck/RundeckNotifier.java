package org.jenkinsci.plugins.rundeck;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Descriptor.FormException;
import hudson.model.Run.Artifact;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.json.JSONObject;

import org.apache.commons.beanutils.Converter;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
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
	private static final Logger LOGGER = Logger.getLogger(RundeckNotifier.class.getName());

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

        RundeckClient rundeck = getDescriptor().getRundeckInstance();

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
            return new RundeckJobProjectLinkerAction(getDescriptor().getRundeckInstance(), jobId);
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
    	private final CopyOnWriteList<RundeckInstallation> installations = new CopyOnWriteList<RundeckInstallation>();


        public RundeckDescriptor() {
            super();
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {

        	installations.replaceBy(req.bindJSONToList(RundeckInstallation.class, json.get("installations")));
        	
        	for(RundeckInstallation installation: installations) {
        		try {
        			RundeckClientBuilder builder = RundeckClient.builder();
        			builder.url(json.getString("url"));
        			if (json.get("authtoken") != null && !"".equals(json.getString("authtoken"))) {
        				builder.token(json.getString("authtoken"));
        			} else {
        				builder.login(json.getString("login"), json.getString("password"));
        			}

        			if (json.optInt("apiversion") > 0) {
        				builder.version(json.getInt("apiversion"));
        			}
        			rundeckInstance=builder.build();
        		} catch (IllegalArgumentException e) {
        			rundeckInstance = null;
        		}
        	}

            save();
            return true; //super.configure(req, json);
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            String jobIdentifier = formData.getString("jobIdentifier");
            RundeckJob job = null;
            try {
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

        public FormValidation doTestConnection(@QueryParameter("rundeck.url") final String url,
                @QueryParameter("rundeck.login") final String login,
                @QueryParameter("rundeck.password") final String password,
                @QueryParameter(value = "rundeck.authtoken", fixEmpty = true) final String token,
                @QueryParameter(value = "rundeck.apiversion", fixEmpty = true) final Integer apiversion) {

            RundeckClient rundeck = null;
            RundeckClientBuilder builder = RundeckClient.builder().url(url);
            if (null != apiversion && apiversion > 0) {
                builder.version(apiversion);
            }else {
                builder.version(RundeckClient.API_VERSION);
            }
            try {
                if (null != token) {
                    rundeck = builder.token(token).build();
                } else {
                    rundeck = builder.login(login, password).build();
                }
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Rundeck configuration is not valid ! %s", e.getMessage());
            }
            try {
                rundeck.ping();
            } catch (RundeckApiException e) {
                return FormValidation.error("We couldn't find a live Rundeck instance at %s", rundeck.getUrl());
            }
            try {
                rundeck.testAuth();
            } catch (RundeckApiLoginException e) {
                return FormValidation.error("Your credentials for the user %s are not valid !", rundeck.getLogin());
            } catch (RundeckApiException.RundeckApiTokenException e) {
                return FormValidation.error("Your token authentication is not valid!");
            }
            return FormValidation.ok("Your Rundeck instance is alive, and your credentials are valid !");
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

        public RundeckClient getRundeckInstance() {
            return rundeckInstance;
        }

        public void setRundeckInstance(RundeckClient rundeckInstance) {
            this.rundeckInstance = rundeckInstance;
        }
    }

    
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        private final CopyOnWriteList<RundeckInstallation> sites = new CopyOnWriteList<RundeckInstallation>();

        public DescriptorImpl() {
            super(RundeckNotifier.class);
            load();
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return Messages.RundeckNotifier.DisplayName();
        }

        public void setSites(RundeckInstallation site) {
            sites.add(site);
        }

        public RundeckInstallation[] getSites() {
            return sites.toArray(new RundeckInstallation[0]);
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
        	RundeckNotifier jpp = req.bindParameters(RundeckNotifier.class, "jira.");
            if (jpp.siteName == null) {
                jpp = null; // not configured
            }
            return jpp;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) {
            //Fix^H^H^HDirty hack for empty string to URL conversion error
            //Should check for existing handler etc, but since this is a dirty hack,
            //we won't
            Stapler.CONVERT_UTILS.deregister(java.net.URL.class);
            Stapler.CONVERT_UTILS.register(new Converter() {
                public Object convert(Class aClass, Object o) {
                    if (o == null || "".equals(o) || "null".equals(o)) {
                        return null;
                    }
                    try {
                        return new URL((String) o);
                    } catch (MalformedURLException e) {
                        LOGGER.warning(String.format("%s is not a valid URL.", o.toString()));
                        return null;
                    }
                }
            }, java.net.URL.class);
            //End hack

            sites.replaceBy(req.bindJSONToList(RundeckInstallation.class, formData.get("sites")));
            save();
            return true;
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
