package org.jenkinsci.plugins.testfairy;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.testfairy.api.APIConnector;
import hudson.scm.ChangeLogSet;
import org.jenkinsci.plugins.testfairy.api.APIException;
import org.jenkinsci.plugins.testfairy.api.APIParams;
import org.jenkinsci.plugins.testfairy.api.APIResponse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.util.EnumSet;
import hudson.remoting.Callable;


public class TestFairyNotifier extends Notifier {

    private APIParams apiParams;

    private String comment;
    private Boolean appendChangelog;

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public String getDisplayName() {
            return "Upload to TestFairy";
        }

        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        public ListBoxModel doFillVideoItems() {
            ListBoxModel items = new ListBoxModel();

            items.add("On", "on");
            items.add("Off", "off");
            items.add("Wifi", "wifi");

            return items;
        }

        public ListBoxModel doFillVideoQualityItems() {
            ListBoxModel items = new ListBoxModel();

            items.add("High", "high");
            items.add("Medium", "medium");
            items.add("Low", "low");

            return items;
        }

        public FormValidation doCheckApiUrl(@QueryParameter String value) {
            return checkRequiredField(value);
        }

        public FormValidation doCheckApiKey(@QueryParameter String value) {
            return checkRequiredField(value);
        }

        public FormValidation doCheckApkFilePath(@QueryParameter String value) {
            if ("".equals(value)) {
                return FormValidation.error("This is a required field");
            }
            return FormValidation.ok();
        }

        private FormValidation checkRequiredField(String value) {
            if ("".equals(value)) {
                return FormValidation.error("This is a required field");
            } else {
                return FormValidation.ok();
            }
        }
    }

    @DataBoundConstructor
    public TestFairyNotifier(String apiUrl, String apiKey, String apkFilePath,
                             String proguardFilePath,
                             String testersGroups, EnumSet<Metric> metrics, String maxDuration,
                             String video, String videoQuality, String videoRate,
                             boolean iconWatermark, String comment, Boolean appendChangelog) {

        this.apiParams = new APIParams(apiUrl, apiKey, apkFilePath, proguardFilePath,
                testersGroups, UI2APIParamsConverter.metricsEnumSetToCSVString(metrics), maxDuration, video, videoQuality, videoRate,
                UI2APIParamsConverter.iconWatermarkBooleanToString(iconWatermark), "");

        this.comment = comment;
        this.appendChangelog = appendChangelog;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)  {

        ConsoleLogger logger =  new ConsoleLogger(listener.getLogger());


        try {
            logger.info("Uploading APK :" + apiParams.getApkFilePath() + " to TestFairy ...");

            apiParams.setComment(createAPKComment(build, apiParams.getComment(), appendChangelog));
            String workspace = getRemoteWorkspacePath(build, logger);
            logger.warn("Workspace is " + workspace);
            Uploader uploader = new Uploader(apiParams, workspace);
            APIResponse response = launcher.getChannel().call(uploader);

            logger.logResponse(response);

            //Continue only if status was ok
            return APIResponse.TEST_FAIRY_STATUS_OK.equals(response.getStatus());

        } catch (APIException e) {

            logger.error("Upload failed: " + e.getMessage());

            //Do NOT continue build
            return false;
        } catch (Exception e) {
            logger.error("Internal error: " + e.getClass().getCanonicalName() + " (" + e.getMessage() + ")\n" +
                    stackTraceToString(e.getStackTrace()));

            //Do NOT continue build
            return false;
        }
    }

    private static String stackTraceToString(StackTraceElement[] stackTrace) {
        StringBuilder builder = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            builder.append(element.getClassName())
                   .append(':')
                   .append(element.getMethodName())
                   .append('(')
                   .append(element.getFileName())
                   .append(':')
                   .append(element.getLineNumber())
                   .append(")")
                   .append("\n");
        }
        return builder.toString();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }


    public String getApiUrl() {
        return apiParams.getApiUrl();
    }

    public String getApiKey() {
        return apiParams.getApiKey();
    }

    public String getApkFilePath() {
        return apiParams.getApkFilePath();
    }

    public String getProguardFilePath() {
        return apiParams.getProguardFilePath();
    }

    public String getTestersGroups() {
        return apiParams.getTestersGroups();
    }

    public EnumSet<Metric> getMetrics(){

        return UI2APIParamsConverter.metricsCSVStringToEnumSet(apiParams.getMetrics()) ;

    }

    public String getMaxDuration() {
        return apiParams.getMaxDuration();
    }

    public String getVideo() {
        return apiParams.getVideo();
    }

    public String getVideoQuality() {
        return apiParams.getVideoQuality();
    }

    public String getVideoRate() {
        return apiParams.getVideoRate();
    }

    public boolean getIconWatermark() {

        return UI2APIParamsConverter.iconWatermarkStringToBoolean(apiParams.getIconWatermark());

    }

    public String getComment() {
        return comment;
    }

    public Boolean getAppendChangelog() {
        return appendChangelog;
    }

    private static String createAPKComment(AbstractBuild<?, ?> build, String comment, Boolean appendChangelog) {
        if (appendChangelog != null && appendChangelog) {
            return getChangeLog(comment, build.getChangeSet());
        } else {
            return comment;
        }
    }

    private String getRemoteWorkspacePath(AbstractBuild<?, ?> build, ConsoleLogger logger) {
        FilePath workspace = build.getWorkspace();
        String path = "";
        if (workspace != null) {
            try {
                path = workspace.toURI().getPath();
            } catch (IOException e) {
                logger.warn("Could not retrive build workspace");
            } catch (InterruptedException e) {
                logger.warn("Could not retrive build workspace");
            }
        }
        return path;
    }


    private  static String getChangeLog(String title, ChangeLogSet<?> changeSet) {
        long lineNumber = 0;
        StringBuilder builder = new StringBuilder(title);
        builder.append("\n\n");
        if (changeSet.isEmptySet()) {
            return builder.append(Messages.TestFairyNotifier_NoChanges()).toString();
        } else {
            builder.append(Messages.TestFairyNotifier_NewChanges()).append('\n');
            for (ChangeLogSet.Entry change : changeSet) {
                builder.append(++lineNumber).append(". ")
                        .append(change.getAuthor().getDisplayName())
                        .append(" - ")
                        .append(change.getMsg())
                        .append('\n');
            }
            return builder.toString();
        }
    }


    private static class Uploader extends APIConnector implements Callable<APIResponse, APIException> {
        private APIParams apiParams;
        private String workspace;

        public Uploader(APIParams apiParams, String workspace) throws APIException {
            super();
            this.apiParams = apiParams;
            this.workspace = workspace;
        }

        @Override
        public APIResponse call() throws APIException {
            apiParams.initializeAndValidate(workspace);
            return sendUploadRequest(apiParams);
        }
    }
}
