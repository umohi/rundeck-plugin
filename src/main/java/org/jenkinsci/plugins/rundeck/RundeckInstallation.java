package org.jenkinsci.plugins.rundeck;

import hudson.model.Hudson;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class RundeckInstallation {

	/**
	 * @return all available installations, never <tt>null</tt>
	 * @since 1.7
	 */
	public static final RundeckInstallation[] all() {
		Hudson hudson = Hudson.getInstance();
		if (hudson == null) {
			// for unit test
			return new RundeckInstallation[0];
		}

		//FIXME:
		//RundeckPublisher.DescriptorImpl rundeckDescriptor = Hudson.getInstance().getDescriptorByType(RundeckPublisher.DescriptorImpl.class);
		//return rundeckDescriptor.getInstallations();
		return new RundeckInstallation[0];
	}

	/**
	 * @return installation by name, <tt>null</tt> if not found
	 * @since 1.7
	 */
	public static final RundeckInstallation get(String name) {
		RundeckInstallation[] available = all();
		if (StringUtils.isEmpty(name) && available.length > 0) {
			return available[0];
		}
		for (RundeckInstallation si : available) {
			if (StringUtils.equals(name, si.getName())) {
				return si;
			}
		}
		return null;
	}

	private final String name;
	private final String url;
	private final String login;
	private final String password;
	private final String token;
	private final Integer apiversion;


	@DataBoundConstructor
	public RundeckInstallation(String name, String url,
			String login, String password,
			String token, Integer apiversion) {
		this.name = name;
		this.url = url;
		this.login = login;
		this.password = password;
		this.token = token;
		this.apiversion = apiversion;
	}

	public String getName() {
		return name;
	}

	public String getUrl() {
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

	public Integer getApiversion() {
		return apiversion;
	}


}

