package org.fusesource.fabric.fab.osgi.internal;

import aQute.lib.osgi.Analyzer;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.fusesource.fabric.fab.DependencyTree;
import org.fusesource.fabric.fab.MavenResolver;
import org.fusesource.fabric.fab.PomDetails;
import org.fusesource.fabric.fab.VersionedDependencyId;
import org.fusesource.fabric.fab.osgi.FabBundleInfo;
import org.fusesource.fabric.fab.osgi.FabResolver;
import org.fusesource.fabric.fab.osgi.FabResolverFactory;
import org.fusesource.fabric.fab.osgi.ServiceConstants;
import org.fusesource.fabric.fab.osgi.util.FeatureCollector;
import org.fusesource.fabric.fab.osgi.util.PruningFilter;
import org.fusesource.fabric.fab.osgi.util.Service;
import org.fusesource.fabric.fab.osgi.util.Services;
import org.fusesource.fabric.fab.util.Files;
import org.fusesource.fabric.fab.util.Filter;
import org.fusesource.fabric.fab.util.Objects;
import org.fusesource.fabric.fab.util.Strings;
import org.ops4j.lang.NullArgumentException;
import org.ops4j.lang.PreConditionException;
import org.osgi.framework.*;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.aether.RepositoryException;
import org.sonatype.aether.graph.Dependency;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

import static org.fusesource.fabric.fab.util.Strings.emptyIfNull;
import static org.fusesource.fabric.fab.util.Strings.notEmpty;

/**
 * Created with IntelliJ IDEA.
 * User: gertv
 * Date: 13/04/12
 * Time: 10:12
 * To change this template use File | Settings | File Templates.
 */
public class FabResolverFactoryImpl implements FabResolverFactory, ServiceProvider {

    private static final transient Logger LOG = LoggerFactory.getLogger(FabResolver.class);

    private BundleContext bundleContext;
    private ConfigurationAdmin configurationAdmin;
    private FeaturesService featuresService;

    @Override
    public BundleContext getBundleContext() {
        return bundleContext;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Override
    public ConfigurationAdmin getConfigurationAdmin() {
        return configurationAdmin;
    }

    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    public FeaturesService getFeaturesService() {
        return featuresService;
    }

    public void setFeaturesService(FeaturesService featuresService) {
        this.featuresService = featuresService;
    }

    @Override
    public FabResolver getResolver(URL url) {
        try {
            return new FabResolverImpl(url);
        } catch (MalformedURLException e) {
            //TODO; figure out how to handle this one
            e.printStackTrace();
            return null;
        }
    }

    private class FabResolverImpl implements FabResolver, FabFacade  {

        private Configuration configuration;
        private final BundleContext bundleContext;
        private PomDetails pomDetails;
        private MavenResolver resolver = new MavenResolver();
        private boolean includeSharedResources = true;
        private FabClassPathResolver classPathResolver;
        private Model model;
        private DependencyTree rootTree;
        private final URL url;

        public FabResolverImpl(URL url) throws MalformedURLException {
            super();
            this.url = url;

            NullArgumentException.validateNotNull(url, "URL");

            this.bundleContext = FabResolverFactoryImpl.this.bundleContext;

            String path = url.getPath();
            if (path == null || path.trim().length() == 0) {
                throw new MalformedURLException("Path cannot empty");
            }
            this.configuration = Configuration.newInstance(FabResolverFactoryImpl.this.configurationAdmin, bundleContext);
            String[] repositories = configuration.getMavenRepositories();
            if (repositories != null) {
                resolver.setRepositories(repositories);
            }
            String localrepo = configuration.getLocalMavenRepository();
            if (localrepo != null) {
                resolver.setLocalRepo(localrepo);
            }
        }

        @Override
        public DependencyTree collectDependencyTree(boolean offline, Filter<Dependency> excludeDependencyFilter) throws RepositoryException, IOException, XmlPullParserException {
            if (rootTree == null) {
                PomDetails details = resolvePomDetails();
                Objects.notNull(details, "pomDetails");
                try {
                    rootTree = getResolver().collectDependencies(details, offline, excludeDependencyFilter).getTree();
                } catch (IOException e) {
                    logFailure(e);
                    throw e;
                } catch (XmlPullParserException e) {
                    logFailure(e);
                    throw e;
                } catch (RepositoryException e) {
                    logFailure(e);
                    throw e;
                }
            }
            return rootTree;
        }

        public void setRootTree(DependencyTree rootTree) {
            this.rootTree = rootTree;
        }

        protected void logFailure(Exception e) {
            LOG.error(e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null && cause != e) {
                LOG.error("Caused by: " + e, e);
            }
        }

        @Override
        public VersionedDependencyId getVersionedDependencyId() throws IOException, XmlPullParserException {
            PomDetails pomDetails = resolvePomDetails();
            if (pomDetails == null || !pomDetails.isValid()) {
                LOG.warn("Cannot resolve pom.xml for " + getJarFile());
                return null;
            }
            model = pomDetails.getModel();
            return new VersionedDependencyId(model);
        }

        @Override
        public String getProjectDescription() {
            if (model != null) {
                return model.getDescription();
            }
            return null;
        }

        public BundleContext getBundleContext() {
            return bundleContext;
        }

        public MavenResolver getResolver() {
            return resolver;
        }

        public PomDetails getPomDetails() {
            return pomDetails;
        }

        public void setPomDetails(PomDetails pomDetails) {
            this.pomDetails = pomDetails;
        }

        @Override
        public File getJarFile() throws IOException {
            return Files.urlToFile(url, "fabric-tmp-fab-", ".fab");
        }

        public boolean isIncludeSharedResources() {
            return includeSharedResources;
        }

        public void setIncludeSharedResources(boolean includeSharedResources) {
            this.includeSharedResources = includeSharedResources;
        }

        /**
         * If the PomDetails has not been resolved yet, try and resolve it
         */
        public PomDetails resolvePomDetails() throws IOException {
            PomDetails pomDetails = getPomDetails();
            if (pomDetails == null) {
                File fileJar = getJarFile();
                pomDetails = getResolver().findPomFile(fileJar);
            }
            return pomDetails;
        }

        @Override
        public FabBundleInfo getInfo() throws IOException {
            try {
                Map<String, Object> embeddedResources = new HashMap<String, Object>();
                Properties instructions = createInstructions(embeddedResources);

                PreConditionException.validateNotNull(instructions, "Instructions");
                String fabUri = instructions.getProperty(ServiceConstants.INSTR_FAB_URL);
                if (fabUri == null || fabUri.trim().length() == 0) {
                    throw new IOException(
                            "Instructions file must contain a property named " + ServiceConstants.INSTR_FAB_URL
                    );
                }

                FabBundleInfo info = new FabBundleInfoImpl(classPathResolver, fabUri, instructions, configuration, embeddedResources, resolvePomDetails());
                return info;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }


        @Override
        public Configuration getConfiguration() {
            return configuration;
        }

        /**
         * Returns the processing instructions
         * @param embeddedResources
         */
        protected Properties createInstructions(Map<String, Object> embeddedResources) throws IOException, RepositoryException, XmlPullParserException, BundleException {
            Properties instructions = BndUtils.parseInstructions(url.getQuery());

            String urlText = url.toExternalForm();
            instructions.setProperty(ServiceConstants.INSTR_FAB_URL, urlText);

            configureInstructions(instructions, embeddedResources);
            return instructions;
        }

        /**
         * Strategy method to allow the instructions to be processed by derived classes
         */
        protected void configureInstructions(Properties instructions, Map<String, Object> embeddedResources) throws RepositoryException, IOException, XmlPullParserException, BundleException {
            classPathResolver = new FabClassPathResolver(this, instructions, embeddedResources);
            classPathResolver.addPruningFilter(new CamelFeaturesFilter(FabResolverFactoryImpl.this.getFeaturesService()));
            classPathResolver.resolve();
        }

        @Override
        public String toVersionRange(String version) {
            int digits = ServiceConstants.DEFAULT_VERSION_DIGITS;
            String value = classPathResolver.getManifestProperty(ServiceConstants.INSTR_FAB_VERSION_RANGE_DIGITS);
            if (notEmpty(value)) {
                try {
                    digits = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    LOG.warn("Failed to parse manifest header " + ServiceConstants.INSTR_FAB_VERSION_RANGE_DIGITS + " as a number. Got: '" + value + "' so ignoring it");
                }
                if (digits < 0 || digits > 4) {
                    LOG.warn("Invalid value of manifest header " + ServiceConstants.INSTR_FAB_VERSION_RANGE_DIGITS + " as value " + digits + " is out of range so ignoring it");
                    digits = ServiceConstants.DEFAULT_VERSION_DIGITS;
                }
            }
            return Versions.toVersionRange(version, digits);
        }

        public boolean isInstalled(DependencyTree tree) {
            return FabFacadeSupport.isInstalled(getBundleContext(), tree);
        }
    }

    /**
     * Filter implementation that matches Camel dependencies to features and collects the found features
     */
    protected static class CamelFeaturesFilter implements PruningFilter, FeatureCollector {

        private final FeaturesService service;
        private final List<String> features = new LinkedList<String>();

        public CamelFeaturesFilter(FeaturesService service) {
            this.service = service;
        }

        @Override
        public boolean matches(DependencyTree dependencyTree) {
            boolean result = false;
            if (dependencyTree.getGroupId().equals("org.apache.camel")) {
                try {
                    Feature feature = service.getFeature(dependencyTree.getArtifactId());
                    if (feature != null) {
                        features.add(String.format("%s/%s", feature.getName(), feature.getVersion()));
                        result = true;
                    }
                } catch (Exception e1) {
                    LOG.debug("Unable to retrieve information about or unable to install Camel feature {} - installing the artifact instead of the feature", dependencyTree.getArtifactId());
                }
            }

            return result;
        }

        @Override
        public String toString() {
            return "filter<replace camel bundles by features>";
        }

        @Override
        public Collection<String> getCollection() {
            return features;
        }

        @Override
        public boolean isEnabled(FabClassPathResolver resolver) {
            return !Strings.splitAndTrimAsList(emptyIfNull(resolver.getManifestProperty(ServiceConstants.INSTR_FAB_SKIP_MATCHING_FEATURE_DETECTION)), "\\s+").contains("org.apache.camel");
        }
    }
}