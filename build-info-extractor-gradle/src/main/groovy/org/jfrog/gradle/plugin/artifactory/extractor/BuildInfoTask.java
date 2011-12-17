/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.gradle.plugin.artifactory.extractor;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import groovy.lang.Closure;
import org.apache.commons.lang.StringUtils;
import org.apache.ivy.core.IvyPatternHelper;
import org.apache.tools.ant.util.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.PublishArtifactSet;
import org.gradle.api.internal.artifacts.publish.DefaultPublishArtifact;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.MavenPluginConvention;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.Upload;
import org.gradle.util.ConfigureUtil;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.client.ArtifactSpec;
import org.jfrog.build.client.ArtifactSpecs;
import org.jfrog.build.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.build.client.DeployDetails;
import org.jfrog.build.client.IncludeExcludePatterns;
import org.jfrog.build.client.LayoutPatterns;
import org.jfrog.build.client.PatternMatcher;
import org.jfrog.build.extractor.BuildInfoExtractorSpec;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryPluginUtil;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.dsl.PropertiesConfig;
import org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * @author Tomer Cohen
 */
public class BuildInfoTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(BuildInfoTask.class);

    public static final String BUILD_INFO_TASK_NAME = "artifactoryPublish";

    public static final String PUBLISH_IVY = "publishIvy";
    public static final String PUBLISH_POM = "publishPom";
    public static final String PUBLISH_ARTIFACTS = "publishArtifacts";
    public static final String PUBLISH_BUILD_INFO = "publishBuildInfo";

    @InputFile
    @Optional
    private File ivyDescriptor;

    @InputFile
    @Optional
    private File mavenDescriptor;

    @InputFiles
    @Optional
    private Set<Configuration> publishConfigurations = Sets.newHashSet();

    @Input
    private final Multimap<String, CharSequence> properties = ArrayListMultimap.create();

    private final Map<String, Boolean> flags = Maps.newHashMap();

    private boolean lastInGraph = false;

    private Map<String, String> defaultProps;
    private ArtifactSpecs artifactSpecs;

    //TODO: [by yl] Task flags are not merged into ArtifactoryClientConfiguration, leading to double checking -
    // against the class and against acc (e.g. isPublishIvy() here)
    @Input
    @Optional
    @Nullable
    public Boolean getPublishBuildInfo() {
        return getFlag(PUBLISH_BUILD_INFO);
    }

    public void setPublishBuildInfo(Object publishBuildInfo) {
        setFlag(PUBLISH_BUILD_INFO, toBoolean(publishBuildInfo));
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishArtifacts() {
        return getFlag(PUBLISH_ARTIFACTS);
    }

    public void setPublishArtifacts(Object publishArtifacts) {
        setFlag(PUBLISH_ARTIFACTS, toBoolean(publishArtifacts));
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishIvy() {
        // TODO: Need to take the default from ACC
        return getFlag(PUBLISH_IVY);
    }

    public void setPublishIvy(Object publishIvy) {
        setFlag(PUBLISH_IVY, toBoolean(publishIvy));
    }

    private Boolean toBoolean(Object publishIvy) {
        return Boolean.valueOf(publishIvy.toString());
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishPom() {
        return getFlag(PUBLISH_POM);
    }

    public void setPublishPom(Object publishPom) {
        setFlag(PUBLISH_POM, toBoolean(publishPom));
    }

    @Nullable
    private Boolean getFlag(String flagName) {
        return flags.get(flagName);
    }

    private void setFlag(String flagName, Boolean newValue) {
        flags.put(flagName, newValue);
    }

    public void setLastInGraph(boolean lastInGraph) {
        this.lastInGraph = lastInGraph;
    }

    public void setProperties(Map<String, CharSequence> props) {
        if (props == null || props.isEmpty()) {
            return;
        }
        for (Map.Entry<String, CharSequence> entry : props.entrySet()) {
            // The key cannot be lazy eval, but we keep the value as GString as long as possible
            String key = entry.getKey();
            if (StringUtils.isNotBlank(key)) {
                CharSequence value = entry.getValue();
                if (value != null) {
                    // Make sure all GString are now Java Strings for key,
                    // and don't call toString for value (keep lazy eval as long as possible)
                    // So, don't use HashMultimap this will call equals on the GString
                    this.properties.put(key, value);
                }
            }
        }
    }

    //For testing
    public ArtifactSpecs getArtifactSpecs() {
        return artifactSpecs;
    }

    public void publishConfigs(Object... confs) {
        if (confs == null) {
            return;
        }
        for (Object conf : confs) {
            if (conf instanceof CharSequence) {
                Configuration projectConf = getProject().getConfigurations().findByName(conf.toString());
                if (projectConf != null) {
                    publishConfigurations.add(projectConf);
                } else {
                    log.info("Configuration named '{}' does not exist for project '{}' in task '{}'",
                            new Object[]{conf, getProject().getPath(), getPath()});
                }
            } else if (conf instanceof Configuration) {
                publishConfigurations.add((Configuration) conf);
            } else {
                log.info("Configuration type '{}' not supported in task '{}'",
                        new Object[]{conf.getClass().getName(), getPath()});
            }
        }
    }

    public Set<Configuration> getPublishConfigurations() {
        return publishConfigurations;
    }

    public Configuration getConfiguration() {
        if (!hasConfigurations()) {
            return null;
        }
        return publishConfigurations.iterator().next();
    }

    public boolean hasConfigurations() {
        return publishConfigurations != null && !publishConfigurations.isEmpty();
    }

    public void setConfiguration(Configuration configuration) {
        if (configuration == null) {
            return;
        }
        if (publishConfigurations == null) {
            publishConfigurations = Sets.newHashSet();
        }
        publishConfigurations.add(configuration);
    }

    public File getIvyDescriptor() {
        return ivyDescriptor;
    }

    public void setIvyDescriptor(Object ivyDescriptor) {
        if (ivyDescriptor != null) {
            if (ivyDescriptor instanceof File) {
                this.ivyDescriptor = (File) ivyDescriptor;
            } else if (ivyDescriptor instanceof CharSequence) {
                if (FileUtils.isAbsolutePath(ivyDescriptor.toString())) {
                    this.ivyDescriptor = new File(ivyDescriptor.toString());
                } else {
                    this.ivyDescriptor = new File(getProject().getProjectDir(), ivyDescriptor.toString());
                }
            } else {
                log.warn("Unknown type '{}' for ivy descriptor in task '{}'",
                        new Object[]{ivyDescriptor.getClass().getName(), getPath()});
            }
        } else {
            this.ivyDescriptor = null;
        }
    }

    public File getMavenDescriptor() {
        return mavenDescriptor;
    }

    public void setMavenDescriptor(Object mavenDescriptor) {
        if (mavenDescriptor != null) {
            if (mavenDescriptor instanceof File) {
                this.mavenDescriptor = (File) mavenDescriptor;
            } else if (mavenDescriptor instanceof CharSequence) {
                if (FileUtils.isAbsolutePath(mavenDescriptor.toString())) {
                    this.mavenDescriptor = new File(mavenDescriptor.toString());
                } else {
                    this.mavenDescriptor = new File(getProject().getProjectDir(), mavenDescriptor.toString());
                }
            } else {
                log.warn("Unknown type '{}' for maven descriptor in task '{}'",
                        new Object[]{mavenDescriptor.getClass().getName(), getPath()});
            }
        } else {
            this.mavenDescriptor = null;
        }
    }


    public void projectsEvaluated() {
        ArtifactoryClientConfiguration acc = getArtifactoryClientConfiguration();
        artifactSpecs = acc.publisher.getArtifactSpecs();

        Project project = getProject();
        ArtifactoryPluginConvention convention = ArtifactoryPluginUtil.getArtifactoryConvention(project);
        //Configure the task using the defaults closure (delegate to the task)
        PublisherConfig config = convention.getPublisherConfig();
        if (config != null) {
            Closure defaultsClosure = config.getDefaultsClosure();
            ConfigureUtil.configure(defaultsClosure, this);
        }

        if (!hasConfigurations()) {
            // If no configurations set and the archives config exists, adding it by default
            Configuration archiveConfig = project.getConfigurations().findByName(Dependency.ARCHIVES_CONFIGURATION);
            setConfiguration(archiveConfig);
        }

        //Depend on buildInfo task in sub-projects
        for (Project sub : project.getSubprojects()) {
            Task subBiTask = sub.getTasks().findByName(BUILD_INFO_TASK_NAME);
            if (subBiTask != null) {
                dependsOn(subBiTask);
            }
        }

        // If no configuration no descriptor
        if (!hasConfigurations()) {
            return;
        }

        // Set ivy descriptor parameters
        TaskContainer tasks = project.getTasks();
        if (isPublishIvy(acc)) {
            Configuration archiveConf = project.getConfigurations().findByName(Dependency.ARCHIVES_CONFIGURATION);
            if (ivyDescriptor == null && archiveConf != null) {
                // Flag to publish the Ivy XML file, but no ivy descriptor file inputted, activate default upload${configuration}.
                // ATTENTION: Tasks not part of the execution graph have withType(Upload.class) false ?!? Need to check for type our self.
                Task candidateUploadTask = tasks.findByName(archiveConf.getUploadTaskName());
                if (candidateUploadTask == null) {
                    log.warn("Cannot publish Ivy descriptor if ivyDescriptor not set in task '{}' " +
                            "and task '{}' does not exist." +
                            "\nAdding \"apply plugin: 'java'\" or any other plugin extending the 'base' plugin will " +
                            "solve this issue.",
                            new Object[]{getPath(), archiveConf.getUploadTaskName()});
                    ivyDescriptor = null;
                } else {
                    if (!(candidateUploadTask instanceof Upload)) {
                        log.warn("Cannot publish Ivy descriptor if ivyDescriptor not set in task '{}' " +
                                "and task '{}' is not an Upload task." +
                                "\nYou'll need to set publishIvy=false or provide a path to the ivy file to publish to " +
                                "solve this issue.",
                                new Object[]{getPath(), archiveConf.getUploadTaskName()});
                        ivyDescriptor = null;
                    } else {
                        Upload uploadTask = (Upload) candidateUploadTask;
                        if (!uploadTask.isUploadDescriptor()) {
                            log.info("Forcing task '{}' to upload its Ivy descriptor (uploadDescriptor was false).",
                                    uploadTask.getPath());
                            uploadTask.setUploadDescriptor(true);
                        }
                        ivyDescriptor = uploadTask.getDescriptorDestination();
                        dependsOn(candidateUploadTask);
                    }
                }
            }
        } else {
            ivyDescriptor = null;
        }

        // Set maven pom parameters
        if (isPublishMaven(acc)) {
            if (mavenDescriptor == null) {
                // Flag to publish the Maven POM, but no pom file inputted, activate default Maven install.
                // if the project doesn't have the maven install task, warn
                Upload installTask = tasks.withType(Upload.class).findByName("install");
                if (installTask == null) {
                    log.warn("Cannot publish pom for project '{}' since it does not contain the Maven " +
                            "plugin install task and task '{}' does not specify a custom pom path.",
                            new Object[]{project.getPath(), getPath()});
                    mavenDescriptor = null;
                } else {
                    mavenDescriptor = new File(
                            project.getConvention().getPlugin(MavenPluginConvention.class).getMavenPomDir(),
                            "pom-default.xml");
                    dependsOn(installTask);
                }
            }
        } else {
            mavenDescriptor = null;
        }
    }

    @Nonnull
    private Boolean isPublishArtifacts(ArtifactoryClientConfiguration acc) {
        Boolean publishArtifacts = getPublishArtifacts();
        if (publishArtifacts == null) {
            return acc.publisher.isPublishArtifacts();
        }
        return publishArtifacts;
    }

    @Nonnull
    private Boolean isPublishBuildInfo(ArtifactoryClientConfiguration acc) {
        Boolean publishBuildInfo = getPublishBuildInfo();
        if (publishBuildInfo == null) {
            return acc.publisher.isPublishBuildInfo();
        }
        return publishBuildInfo;
    }

    @Nonnull
    private Boolean isPublishMaven(ArtifactoryClientConfiguration acc) {
        Boolean publishPom = getPublishPom();
        if (publishPom == null) {
            return acc.publisher.isMaven();
        }
        return publishPom;
    }

    @Nonnull
    private Boolean isPublishIvy(ArtifactoryClientConfiguration acc) {
        Boolean publishIvy = getPublishIvy();
        if (publishIvy == null) {
            return acc.publisher.isIvy();
        }
        return publishIvy;
    }

    private ArtifactoryClientConfiguration getArtifactoryClientConfiguration() {
        return ArtifactoryPluginUtil.getArtifactoryConvention(getProject()).getClientConfig();
    }

    @TaskAction
    public void collectProjectBuildInfo() throws IOException {
        log.debug("Task '{}' activated", getPath());
        // Only the last buildInfo execution activate the deployment
        if (lastInGraph) {
            log.debug("Starting build info extraction for project '{}' using last task in graph '{}'",
                    new Object[]{getProject().getPath(), getPath()});
            prepareAndDeploy();
        }
    }

    public void properties(Closure closure) {
        Project project = getProject();
        PropertiesConfig propertiesConfig = new PropertiesConfig(project);
        ConfigureUtil.configure(closure, propertiesConfig);
        artifactSpecs.addAll(propertiesConfig.getArtifactSpecs());
    }

    private void collectDescriptorsAndArtifactsForUpload(Set<GradleDeployDetails> allDeployableDetails)
            throws IOException {
        Set<GradleDeployDetails> deployDetailsFromProject = getArtifactDeployDetailsFromClientConf();
        allDeployableDetails.addAll(deployDetailsFromProject);

        //Add the ivy and maven descriptors if they exist
        ArtifactoryClientConfiguration acc = getArtifactoryClientConfiguration();
        if (isPublishIvy(acc)) {
            if (ivyDescriptor != null && ivyDescriptor.exists()) {
                allDeployableDetails.add(getIvyDescriptorDeployDetails());
            }
        }
        if (isPublishMaven(acc)) {
            if (mavenDescriptor != null && mavenDescriptor.exists()) {
                allDeployableDetails.add(getMavenDeployDetails());
            }
        }
    }

    private GradleDeployDetails getIvyDescriptorDeployDetails() {
        ArtifactoryClientConfiguration clientConf = getArtifactoryClientConfiguration();
        DeployDetails.Builder artifactBuilder = new DeployDetails.Builder().file(ivyDescriptor);
        try {
            Map<String, String> checksums =
                    FileChecksumCalculator.calculateChecksums(ivyDescriptor, "MD5", "SHA1");
            artifactBuilder.md5(checksums.get("MD5")).sha1(checksums.get("SHA1"));
        } catch (Exception e) {
            throw new GradleException(
                    "Failed to calculated checksums for artifact: " + ivyDescriptor.getAbsolutePath(), e);
        }
        String gid = getProject().getGroup().toString();
        if (clientConf.publisher.isM2Compatible()) {
            gid = gid.replace(".", "/");
        }
        artifactBuilder.artifactPath(IvyPatternHelper
                .substitute(clientConf.publisher.getIvyPattern(), gid, getProject().getName(),
                        getProject().getVersion().toString(), null, "ivy", "xml"));
        artifactBuilder.targetRepository(clientConf.publisher.getRepoKey());
        DefaultPublishArtifact artifact =
                new DefaultPublishArtifact(ivyDescriptor.getName(), "xml", "ivy", null, null, ivyDescriptor);
        Map<String, String> propsToAdd = getPropsToAdd(artifact);
        artifactBuilder.addProperties(propsToAdd);
        return new GradleDeployDetails(artifact, artifactBuilder.build(), getProject());
    }

    private GradleDeployDetails getMavenDeployDetails() {
        ArtifactoryClientConfiguration clientConf = getArtifactoryClientConfiguration();
        DeployDetails.Builder artifactBuilder = new DeployDetails.Builder().file(mavenDescriptor);
        try {
            Map<String, String> checksums =
                    FileChecksumCalculator.calculateChecksums(mavenDescriptor, "MD5", "SHA1");
            artifactBuilder.md5(checksums.get("MD5")).sha1(checksums.get("SHA1"));
        } catch (Exception e) {
            throw new GradleException(
                    "Failed to calculated checksums for artifact: " + mavenDescriptor.getAbsolutePath(), e);
        }
        // for pom files always enforce the M2 pattern
        artifactBuilder.artifactPath(IvyPatternHelper.substitute(LayoutPatterns.M2_PATTERN,
                getProject().getGroup().toString().replace(".", "/"), getProject().getName(),
                getProject().getVersion().toString(), null, "pom", "pom"));
        artifactBuilder.targetRepository(clientConf.publisher.getRepoKey());
        DefaultPublishArtifact artifact =
                new DefaultPublishArtifact(mavenDescriptor.getName(), "pom", "pom", null, null, mavenDescriptor);
        Map<String, String> propsToAdd = getPropsToAdd(artifact);
        artifactBuilder.addProperties(propsToAdd);
        return new GradleDeployDetails(artifact, artifactBuilder.build(), getProject());
    }

    /**
     * This method will be activated only at the end of the build, when we reached the root project.
     *
     * @throws java.io.IOException In case the deployment fails.
     */
    private void prepareAndDeploy() throws IOException {
        ArtifactoryClientConfiguration acc = getArtifactoryClientConfiguration();
        String contextUrl = acc.publisher.getContextUrl();
        log.debug("Context URL for deployment '{}", contextUrl);
        String username = acc.publisher.getUsername();
        String password = acc.publisher.getPassword();
        if (StringUtils.isBlank(username)) {
            username = "";
        }
        if (StringUtils.isBlank(password)) {
            password = "";
        }
        ArtifactoryBuildInfoClient client =
                new ArtifactoryBuildInfoClient(contextUrl, username, password, new GradleClientLogger(log));
        Set<GradleDeployDetails> allDeployableDetails = Sets.newHashSet();

        //Update the artifacts
        for (Task birt : getProject().getTasksByName(BUILD_INFO_TASK_NAME, true)) {
            ((BuildInfoTask) birt).collectDescriptorsAndArtifactsForUpload(allDeployableDetails);
        }
        try {
            if (acc.publisher.isPublishArtifacts()) {
                log.debug("Uploading artifacts to Artifactory at '{}'", contextUrl);
                /**
                 * if the {@link org.jfrog.build.client.ClientProperties#PROP_PUBLISH_ARTIFACT} is set the true,
                 * The uploadArchives task will be triggered ONLY at the end, ensuring that the artifacts will be
                 * published only after a successful build. This is done before the build-info is sent.
                 */

                IncludeExcludePatterns patterns = new IncludeExcludePatterns(
                        acc.publisher.getIncludePatterns(),
                        acc.publisher.getExcludePatterns());
                configureProxy(acc, client);
                deployArtifacts(client, allDeployableDetails, patterns);
            }

            //Extract build info and update the clientConf info accordingly (build name, num, etc.)
            GradleBuildInfoExtractor gbie = new GradleBuildInfoExtractor(acc, allDeployableDetails);
            Build build = gbie.extract(getProject().getRootProject(), new BuildInfoExtractorSpec());
            /**
             * The build-info will be always written to a file in its JSON form.
             */
            exportBuildInfo(build, getExportFile(acc));
            if (isPublishBuildInfo(acc)) {
                log.debug("Publishing build info to artifactory at: '{}'", contextUrl);
                /**
                 * After all the artifacts were uploaded successfully the next task is to send the build-info
                 * object.
                 */
                // If export property set always save the file before sending it to artifactory
                exportBuildInfo(build, getExportFile(acc));
                client.sendBuildInfo(build);
            }
        } finally {
            client.shutdown();
        }
    }

    private File getExportFile(ArtifactoryClientConfiguration clientConf) {
        String fileExportPath = clientConf.getExportFile();
        if (StringUtils.isNotBlank(fileExportPath)) {
            return new File(fileExportPath);
        }
        Project rootProject = getProject().getRootProject();
        return new File(rootProject.getBuildDir(), "build-info.json");
    }

    private void configureProxy(ArtifactoryClientConfiguration clientConf, ArtifactoryBuildInfoClient client) {
        ArtifactoryClientConfiguration.ProxyHandler proxy = clientConf.proxy;
        String proxyHost = proxy.getHost();
        if (StringUtils.isNotBlank(proxyHost) && proxy.getPort() != null) {
            log.debug("Found proxy host '{}'", proxyHost);
            String proxyUserName = proxy.getUsername();
            if (StringUtils.isNotBlank(proxyUserName)) {
                log.debug("Found proxy user name '{}'", proxyUserName);
                client.setProxyConfiguration(proxyHost, proxy.getPort(), proxyUserName, proxy.getPassword());
            } else {
                log.debug("No proxy user name and password found, using anonymous proxy");
                client.setProxyConfiguration(proxyHost, proxy.getPort());
            }
        }
    }

    private void deployArtifacts(ArtifactoryBuildInfoClient client, Set<GradleDeployDetails> details,
            IncludeExcludePatterns patterns) throws IOException {
        for (GradleDeployDetails detail : details) {
            DeployDetails deployDetails = detail.getDeployDetails();
            String artifactPath = deployDetails.getArtifactPath();
            if (PatternMatcher.pathConflicts(artifactPath, patterns)) {
                log.log(LogLevel.LIFECYCLE, "Skipping the deployment of '" + artifactPath +
                        "' due to the defined include-exclude patterns.");
                continue;
            }
            client.deployArtifact(deployDetails);
        }
    }

    private void exportBuildInfo(Build build, File toFile) throws IOException {
        log.debug("Exporting generated build info to '{}'", toFile.getAbsolutePath());
        BuildInfoExtractorUtils.saveBuildInfoToFile(build, toFile);
    }

    private Set<GradleDeployDetails> getArtifactDeployDetailsFromClientConf() {
        ArtifactoryClientConfiguration clientConf = getArtifactoryClientConfiguration();

        Set<GradleDeployDetails> deployDetails = Sets.newLinkedHashSet();
        if (!isPublishArtifacts(clientConf)) {
            return deployDetails;
        }
        if (!hasConfigurations()) {
            return deployDetails;
        }
        ArtifactoryClientConfiguration.PublisherHandler publisherConf = clientConf.publisher;
        String pattern = publisherConf.getIvyArtifactPattern();
        String gid = getProject().getGroup().toString();
        if (publisherConf.isM2Compatible()) {
            gid = gid.replace(".", "/");
        }

        for (Configuration configuration : publishConfigurations) {
            PublishArtifactSet artifacts = configuration.getAllArtifacts();
            for (PublishArtifact artifact : artifacts) {
                File file = artifact.getFile();
                DeployDetails.Builder artifactBuilder = new DeployDetails.Builder().file(file);
                try {
                    Map<String, String> checksums =
                            FileChecksumCalculator.calculateChecksums(file, "MD5", "SHA1");
                    artifactBuilder.md5(checksums.get("MD5")).sha1(checksums.get("SHA1"));
                } catch (Exception e) {
                    throw new GradleException(
                            "Failed to calculated checksums for artifact: " + file.getAbsolutePath(), e);
                }
                String revision = getProject().getVersion().toString();
                Map<String, String> extraTokens = Maps.newHashMap();
                if (StringUtils.isNotBlank(artifact.getClassifier())) {
                    extraTokens.put("classifier", artifact.getClassifier());
                }
                artifactBuilder.artifactPath(IvyPatternHelper.substitute(pattern, gid, getProject().getName(),
                        revision, artifact.getName(), artifact.getType(),
                        artifact.getExtension(), configuration.getName(),
                        extraTokens, null));
                artifactBuilder.targetRepository(publisherConf.getRepoKey());
                Map<String, String> propsToAdd = getPropsToAdd(artifact);
                artifactBuilder.addProperties(propsToAdd);
                DeployDetails details = artifactBuilder.build();
                deployDetails.add(new GradleDeployDetails(artifact, details, getProject()));
            }
        }
        return deployDetails;
    }

    private Map<String, String> getPropsToAdd(PublishArtifact artifact) {
        if (defaultProps == null) {
            defaultProps = Maps.newHashMap();
            addProps(defaultProps, properties);
            //Add the publisher properties
            ArtifactoryClientConfiguration clientConf = getArtifactoryClientConfiguration();
            defaultProps.putAll(clientConf.publisher.getMatrixParams());
        }

        Map<String, String> propsToAdd = Maps.newHashMap(defaultProps);
        //Apply artifact-specific props from the artifact specs
        Project project = getProject();
        ArtifactSpec spec =
                ArtifactSpec.builder().configuration(getConfiguration().getName()).group(project.getGroup().toString())
                        .name(artifact.getName()).version(project.getVersion().toString())
                        .classifier(artifact.getClassifier())
                        .type(artifact.getExtension()).build();
        Multimap<String, CharSequence> artifactSpecsProperties = artifactSpecs.getProperties(spec);
        addProps(propsToAdd, artifactSpecsProperties);
        return propsToAdd;
    }

    private void addProps(Map<String, String> target, Multimap<String, CharSequence> props) {
        for (Map.Entry<String, CharSequence> entry : props.entries()) {
            // Make sure all GString are now Java Strings
            String key = entry.getKey();
            String value = entry.getValue().toString();
            //Accumulate multi-value props
            if (!target.containsKey(key)) {
                target.put(key, value);
            } else {
                value = target.get(key) + ", " + value;
                target.put(key, value);
            }
        }
    }
}