package org.jenkinsci.plugins.rundeck;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.rundeck.api.RundeckApiException;
import org.rundeck.api.RundeckClient;
import org.rundeck.api.RundeckClientBuilder;
import org.rundeck.api.RundeckApiException.RundeckApiLoginException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents an external Rundeck installation and configuration
 * needed to access this Rundeck.
 *
 */
public class RundeckSite extends AbstractDescribableImpl<RundeckSite> {

	private final String siteName;
	private final URL url;
	private final String login;
	private final String password;
	private final String token;
	private final Integer version;



	@DataBoundConstructor
    public RundeckSite(String siteName, URL url, String login, String password, String token, Integer version) {
        if (!url.toExternalForm().endsWith("/"))
            try {
                url = new URL(url.toExternalForm() + "/");
            } catch (MalformedURLException e) {
                throw new AssertionError(e);
            }

        this.siteName = Util.fixEmpty(siteName);
        this.url = url;
        this.login = Util.fixEmpty(login);
        this.password = Util.fixEmpty(password);

        this.token = token;
        this.version = version;
    }

    public String getSiteName() {
        return siteName;
    }

    public URL getUrl() {
		return url;
	}

	public String getLogin() {
		return login;
	}

	public String getPassword() {
		return password;
	}

	public String getToken() {
		return token;
	}

	public Integer getVersion() {
		return version;
	}
    /**
     * Gets the effective {@link RundeckSite} associated with the given project.
     *
     * @return null
     *         if no such was found.
     */
    public static RundeckSite get(AbstractProject<?, ?> p) {
        RundeckProjectProperty jpp = p.getProperty(RundeckProjectProperty.class);
        if (jpp != null) {
            RundeckSite site = jpp.getSite();
            if (site != null) {
                return site;
            }
        }

        // none is explicitly configured. try the default ---
        // if only one is configured, that must be it.
        RundeckSite[] sites = RundeckProjectProperty.DESCRIPTOR.getSites();
        if (sites.length == 1) {
            return sites[0];
        }

        return null;
    }



    @Extension
    public static class DescriptorImpl extends Descriptor<RundeckSite> {
        @Override
        public String getDisplayName() {
            return "Rundeck Site";
        }

        /**
         * Checks if the Rundeck URL is accessible and exists.
         */
        public FormValidation doUrlCheck(@QueryParameter final String value)
                throws IOException{
            // this can be used to check existence of any file in any URL, so
            // admin only
            if (!Hudson.getInstance().hasPermission(Hudson.ADMINISTER))
                return FormValidation.ok();

            return new FormValidation.URLCheck() {
                @Override
                protected FormValidation check(){
                    String url = Util.fixEmpty(value);
                    if (url == null) {
                        return FormValidation.error("URL mandatory");
                    }
                    
                    return FormValidation.ok();
                }
            }.check();
        }

        public FormValidation doCheckUserPattern(@QueryParameter String value) throws IOException {
            String userPattern = Util.fixEmpty(value);
            if (userPattern == null) {// userPattern not entered yet
                return FormValidation.ok();
            }
            try {
                Pattern.compile(userPattern);
                return FormValidation.ok();
            } catch (PatternSyntaxException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        /**
         * Checks if the user name and password are valid.
         */
        public FormValidation doValidate(@QueryParameter String siteName,
        								 @QueryParameter String login,
                                         @QueryParameter String url,
                                         @QueryParameter String password,
                                         @QueryParameter String token,
                                         @QueryParameter Integer version)
                throws IOException {
            url = Util.fixEmpty(url);
            if (url == null) {// URL not entered yet
                return FormValidation.error("No URL given");
            }

            RundeckSite site = new RundeckSite(siteName, new URL(url), login, password, token, version);
            
            // FIXME:
            return FormValidation.ok("Success");
            
        }
        
        public FormValidation doTestConnection(@QueryParameter final String siteName,
        		@QueryParameter final String url,
                @QueryParameter final String login,
                @QueryParameter final String password,
                @QueryParameter(fixEmpty = true) final String token,
                @QueryParameter(fixEmpty = true) final Integer version) {

        	LOGGER.fine("[siteName=" + siteName +
        			"] [url=" + url +
        			"] [login=" + login +
        			"] [password=" + password+
        			"] [token=" + token+
        			"] [version=" + version
        			);

        	System.out.println("[siteName=" + siteName +
        			"] [url=" + url +
        			"] [login=" + login +
        			"] [password=" + password+
        			"] [token=" + token+
        			"] [version=" + version
        			);
            RundeckClient rundeck = null;
            RundeckClientBuilder builder = RundeckClient.builder().url(url);
            if (null != version && version > 0) {
                builder.version(version);
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
    }

    private static final Logger LOGGER = Logger.getLogger(RundeckSite.class.getName());
}
