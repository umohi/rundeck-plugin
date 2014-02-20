package org.jenkinsci.plugins.rundeck;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.CopyOnWriteList;
import net.sf.json.JSONObject;
import org.apache.commons.beanutils.Converter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

/**
 * Associates {@link AbstractProject} with {@link RundeckSite}.
 *
 */
public class RundeckProjectProperty extends JobProperty<AbstractProject<?, ?>> {

    /**
     * Used to find {@link RundeckSite}. Matches {@link RundeckSite#getSiteName()}. Always
     * non-null (but beware that this value might become stale if the system
     * config is changed.)
     */
    public final String siteName;

    @DataBoundConstructor
    public RundeckProjectProperty(String siteName) {
        if (siteName == null) {
            // defaults to the first one
        	RundeckSite[] sites = DESCRIPTOR.getSites();
            if (sites.length > 0) {
                siteName = sites[0].getSiteName();
            }
        }
        this.siteName = siteName;
    }

    /**
     * Gets the {@link RundeckSite} that this project belongs to.
     *
     * @return null if the configuration becomes out of sync.
     */
    public RundeckSite getSite() {
        RundeckSite[] sites = DESCRIPTOR.getSites();
        if (siteName == null && sites.length > 0) {
            // default
            return sites[0];
        }

        for (RundeckSite site : sites) {
            if (site.getSiteName().equals(siteName)) {
                return site;
            }
        }
        return null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends JobPropertyDescriptor {
        private final CopyOnWriteList<RundeckSite> sites = new CopyOnWriteList<RundeckSite>();

        public DescriptorImpl() {
            super(RundeckProjectProperty.class);
            load();
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Rundeck";
        }

        public void setSites(RundeckSite site) {
            sites.add(site);
        }

        public RundeckSite[] getSites() {
            return sites.toArray(new RundeckSite[0]);
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
            RundeckProjectProperty jpp = req.bindParameters(RundeckProjectProperty.class, "rundeck.");
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

            sites.replaceBy(req.bindJSONToList(RundeckSite.class, formData.get("sites")));
            save();
            return true;
        }
    }

    private static final Logger LOGGER = Logger
            .getLogger(RundeckProjectProperty.class.getName());
}
