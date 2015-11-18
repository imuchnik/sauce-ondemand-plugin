package hudson.plugins.sauce_ondemand;

import com.saucelabs.ci.JobInformation;
import com.saucelabs.ci.sauceconnect.SauceConnectFourManager;
import com.saucelabs.hudson.HudsonSauceManagerFactory;
import hudson.FilePath;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixRun;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import jenkins.model.Jenkins;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipOutputStream;

/**
 * Backing logic for the Sauce UI component displayed on the Jenkins project page.
 *
 * @author Ross Rowe
 */
public class SauceOnDemandProjectAction extends AbstractAction {

    /**
     * Logger instance.
     */
    private static final Logger logger = Logger.getLogger(SauceOnDemandProjectAction.class.getName());

    /**
     * The Jenkins project that is being displayed.
     */
    private AbstractProject<?, ?> project;

    /**
     * Constructs a new instance.
     *
     * @param project the Jenkins project that is being displayed
     */
    public SauceOnDemandProjectAction(AbstractProject<?, ?> project) {
        this.project = project;
    }

    /**
     * @return The Jenkins project that is being displayed
     */
    public AbstractProject<?, ?> getProject() {
        return project;
    }

    /**
     * @return Whether sauce results were found for this project/builds
     */
    public boolean hasSauceOnDemandResults() {
        logger.fine("checking if project has sauce results");
        if (isSauceEnabled()) {
            logger.fine("Checking to see if project has Sauce results");
            List<SauceOnDemandBuildAction> sauceOnDemandBuildActions = getSauceBuildActions();
            if (sauceOnDemandBuildActions != null) {
                boolean result = false;
                for (SauceOnDemandBuildAction action : sauceOnDemandBuildActions) {
                    if (action.hasSauceOnDemandResults()) {
                        logger.fine("Found Sauce results");
                        result = true;
                        break;
                    }
                }
                logger.fine("hasSauceOnDemandResults: " + result);
                return result;
            }
        }
        logger.fine("Did not find Sauce results");
        return false;
    }

    private SauceOnDemandBuildWrapper getBuildWrapper() {
        return SauceEnvironmentUtil.getBuildWrapper(project);
    }

    /**
     *
     * @return boolean indicating whether the build is configured to include Sauce support
     */
    public boolean isSauceEnabled() {
        return getBuildWrapper() != null;
    }

    private List<SauceOnDemandBuildAction> getSauceBuildActions() {
        AbstractBuild<?, ?> build = getProject().getLastBuild();
        if (build != null) {
            if (build instanceof MatrixBuild) {
                List<SauceOnDemandBuildAction> buildActions = new ArrayList<SauceOnDemandBuildAction>();
                MatrixBuild matrixBuild = (MatrixBuild) build;
                for (MatrixRun matrixRun : matrixBuild.getRuns()) {
                    SauceOnDemandBuildAction buildAction = matrixRun.getAction(SauceOnDemandBuildAction.class);
                    if (buildAction != null) {
                        buildActions.add(buildAction);
                    }
                }
                return buildActions;
            } else {
                SauceOnDemandBuildAction buildAction = build.getAction(SauceOnDemandBuildAction.class);
                if (buildAction == null) {
                    logger.fine("No Sauce Build Action found for " + build.toString() + " adding a new one");
                    return Collections.emptyList();
                }
                return Collections.singletonList(buildAction);
            }
        }

        return Collections.emptyList();
    }

    public List<JobInformation> getJobs() {
        List<SauceOnDemandBuildAction> sauceOnDemandBuildAction = getSauceBuildActions();
        if (sauceOnDemandBuildAction != null) {
            List<JobInformation> allJobs = new ArrayList<JobInformation>();
            for (SauceOnDemandBuildAction action : sauceOnDemandBuildAction) {
                allJobs.addAll(action.getJobs());
            }
            return allJobs;
        }
        logger.fine("No Sauce jobs found");
        return Collections.emptyList();
    }

    public void doGenerateSupportZip(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, IllegalAccessException, NoSuchMethodException, InvocationTargetException, InterruptedException {
        SauceConnectFourManager manager = HudsonSauceManagerFactory.getInstance().createSauceConnectFourManager();
        SauceOnDemandBuildWrapper sauceBuildWrapper = getBuildWrapper();
        AbstractBuild build = getProject().getLastBuild();

        //jenkins.checkPermission(Jenkins.READ);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(baos);
        zipOutputStream.setLevel(ZipOutputStream.STORED);

        BuildSupportZipUtils.addFileToZipStream(zipOutputStream, FileUtils.readFileToByteArray(build.getLogFile()), "build.log");

        BuildSupportZipUtils.buildSauceConnectLog(zipOutputStream, manager, build, sauceBuildWrapper);
        BuildSupportZipUtils.buildWrapperConfigTxt(zipOutputStream, sauceBuildWrapper);
        BuildSupportZipUtils.buildGlobalConfigTxt(zipOutputStream);

        zipOutputStream.finish();
        zipOutputStream.flush();

        rsp.setContentType("application/zip");
        rsp.addHeader("Content-Disposition", "attachment; filename=\"sauce_support.zip\"");
        rsp.addHeader("Content-Transfer-Encoding", "binary");
        rsp.getOutputStream().write(baos.toByteArray());
        rsp.getOutputStream().flush();

    }

    public static class BuildSupportZipUtils {
        public static void buildSauceConnectLog(ZipOutputStream zipOutputStream, SauceConnectFourManager manager, AbstractBuild build, SauceOnDemandBuildWrapper sauceBuildWrapper) throws IOException {
            if (sauceBuildWrapper.isEnableSauceConnect()) {
                File sauceConnectLogFile = manager.getSauceConnectLogFile(sauceBuildWrapper.getOptions());
                if (sauceBuildWrapper.isLaunchSauceConnectOnSlave()) {
                    FilePath fp = new FilePath(build.getBuiltOn().getChannel(), sauceConnectLogFile.getPath());
                    addFileToZipStream(zipOutputStream, fp.readToString().getBytes("UTF-8"), "sc.log");
                } else if (sauceConnectLogFile != null) {
                    addFileToZipStream(zipOutputStream, FileUtils.readFileToByteArray(sauceConnectLogFile), "sc.log");
                }
            }
        }

        public static void buildGlobalConfigTxt(ZipOutputStream zipOutputStream) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException, IOException {
            StringBuilder pluginImplSB = new StringBuilder();
            Iterator bpluginImplIterator = BeanUtils.describe(PluginImpl.get()).entrySet().iterator();
            while (bpluginImplIterator.hasNext())
            {
                Map.Entry entry = (Map.Entry) bpluginImplIterator.next();
                if (entry.getKey().equals("class") || entry.getKey().equals("descriptor")) { continue; }
                pluginImplSB.append(entry.getKey() + "=" + entry.getValue() + "\r\n");
            }
            pluginImplSB.append("version=" + PluginImpl.get().getWrapper().getVersion() + "\r\n");
            pluginImplSB.append("jenkinsVersion=" + Jenkins.VERSION + "\r\n");


            addFileToZipStream(zipOutputStream, pluginImplSB.toString().getBytes("UTF-8"), "global_sauce_config.txt");
        }

        public static void buildWrapperConfigTxt(ZipOutputStream zipOutputStream, SauceOnDemandBuildWrapper sauceBuildWrapper) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException, IOException {
            StringBuilder buildWrapperSB = new StringBuilder();
            Iterator buildWrapperIterator = BeanUtils.describe(sauceBuildWrapper).entrySet().iterator();
            while (buildWrapperIterator.hasNext())
            {
                Map.Entry entry = (Map.Entry) buildWrapperIterator.next();
                if (entry.getKey().equals("class") || entry.getKey().equals("descriptor")) { continue; }
                buildWrapperSB.append(entry.getKey() + "=" + entry.getValue() + "\r\n");
            }
            addFileToZipStream(zipOutputStream, buildWrapperSB.toString().getBytes("UTF-8"), "build_wrapper_config.txt");
        }

        private static void addFileToZipStream(ZipOutputStream zipOutputStream, byte[] bytes, String filename) throws IOException {
            ZipArchiveEntry zipEntry = new ZipArchiveEntry(filename);
            zipOutputStream.putNextEntry(zipEntry);
            zipOutputStream.write(bytes);
            zipOutputStream.flush();
            zipOutputStream.closeEntry();
        }
    }


}
