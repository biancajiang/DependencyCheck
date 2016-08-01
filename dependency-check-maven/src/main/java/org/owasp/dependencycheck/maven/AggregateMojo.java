/*
 * This file is part of dependency-check-maven.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2013 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.owasp.dependencycheck.analyzer.DependencyBundlingAnalyzer;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.data.nvdcve.DatabaseException;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.ExceptionCollection;
import org.owasp.dependencycheck.exception.ReportException;
import org.owasp.dependencycheck.utils.Settings;

/**
 * Maven Plugin that checks project dependencies and the dependencies of all
 * child modules to see if they have any known published vulnerabilities.
 *
 * @author Jeremy Long
 */
@Mojo(
        name = "aggregate",
        defaultPhase = LifecyclePhase.VERIFY,
        /*aggregator = true,*/
        threadSafe = false,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresOnline = true
)
public class AggregateMojo extends BaseDependencyCheckMojo {

    /**
     * The key to store aggregate exception in the root Maven execution context.
     */
    private static final String AGGREGATE_EXCEPTIONS = "AggregateExceptions";

    /**
     * Executes the aggregate dependency-check goal. This runs dependency-check
     * and generates the subsequent reports.
     *
     * @throws MojoExecutionException thrown if there is ane exception running
     * the mojo
     * @throws MojoFailureException thrown if dependency-check is configured to
     * fail the build
     */
    @Override
    public void runCheck() throws MojoExecutionException, MojoFailureException {
        final MavenEngine engine = generateDataFile();
        if (engine == null) {
            return;
        }

        if (getProject() == getLastProject()) {
            //ensure that the .ser file was created for each.
            for (MavenProject current : getReactorProjects()) {
                final File dataFile = getDataFile(current);
                if (dataFile == null && !skipProject(current)) { //dc was never run on this project. write the ser to the target.
                    getLog().error(String.format("Module '%s' did not execute dependency-check; an attempt will be made to perform "
                            + "the check but dependencies may be missed resulting in false negatives.", current.getName()));
                    generateDataFile(engine, current);
                }
            }
            for (MavenProject current : getReactorProjects()) {
                List<Dependency> dependencies = readDataFile(current);
                if (dependencies == null) {
                    dependencies = new ArrayList<Dependency>();
                }
                final Set<MavenProject> childProjects = getDescendants(current);
                for (MavenProject reportOn : childProjects) {
                    final List<Dependency> childDeps = readDataFile(reportOn);
                    if (childDeps != null && !childDeps.isEmpty()) {
                        if (getLog().isDebugEnabled()) {
                            getLog().debug(String.format("Adding %d dependencies from %s", childDeps.size(), reportOn.getName()));
                        }
                        dependencies.addAll(childDeps);
                    } else if (getLog().isDebugEnabled()) {
                        getLog().debug(String.format("No dependencies read for %s", reportOn.getName()));
                    }
                }
                engine.getDependencies().clear();
                engine.getDependencies().addAll(dependencies);
                final DependencyBundlingAnalyzer bundler = new DependencyBundlingAnalyzer();
                try {
                    if (getLog().isDebugEnabled()) {
                        getLog().debug(String.format("Dependency count pre-bundler: %s", engine.getDependencies().size()));
                    }
                    bundler.analyze(null, engine);
                    if (getLog().isDebugEnabled()) {
                        getLog().debug(String.format("Dependency count post-bundler: %s", engine.getDependencies().size()));
                    }
                } catch (AnalysisException ex) {
                    getLog().warn("An error occurred grouping the dependencies; duplicate entries may exist in the report", ex);
                    getLog().debug("Bundling Exception", ex);
                }

                File outputDir = getCorrectOutputDirectory(current);
                if (outputDir == null) {
                    //in some regards we shouldn't be writting this, but we are anyway.
                    //we shouldn't write this because nothing is configured to generate this report.
                    outputDir = new File(current.getBuild().getDirectory());
                }
                try {
                    writeReports(engine, current, outputDir);
                } catch (ReportException ex) {
                    ExceptionCollection exCol = (ExceptionCollection) engine.getExecutionRoot().getContextValue(AGGREGATE_EXCEPTIONS);
                    if (exCol == null) {
                        exCol = new ExceptionCollection("Error writing aggregate report", ex);
                    } else {
                        exCol.addException(ex);
                    }
                    if (this.isFailOnError()) {
                        throw new MojoExecutionException("One or more exceptions occured during dependency-check analysis", exCol);
                    } else {
                        getLog().debug("One or more exceptions occured during dependency-check analysis", exCol);
                    }
                }
            }
        }
        engine.cleanup();
        Settings.cleanup();
    }

    /**
     * Gets the last project in the reactor - taking into account skipped
     * projects.
     *
     * @return the last project in the reactor
     */
    private MavenProject getLastProject() {
        for (int x = getReactorProjects().size() - 1; x >= 0; x--) {
            final MavenProject p = getReactorProjects().get(x);
            if (!skipProject(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Tests if the project is being skipped in the Maven site report.
     *
     * @param project a project in the reactor
     * @return true if the project is skipped; otherwise false
     */
    private boolean skipProject(MavenProject project) {
        final String skip = (String) project.getProperties().get("maven.site.skip");
        return "true".equalsIgnoreCase(skip) && isGeneratingSite();
    }

    /**
     * Returns a set containing all the descendant projects of the given
     * project.
     *
     * @param project the project for which all descendants will be returned
     * @return the set of descendant projects
     */
    protected Set<MavenProject> getDescendants(MavenProject project) {
        if (project == null) {
            return Collections.emptySet();
        }
        final Set<MavenProject> descendants = new HashSet<MavenProject>();
        int size = 0;
        if (getLog().isDebugEnabled()) {
            getLog().debug(String.format("Collecting descendants of %s", project.getName()));
        }
        for (String m : project.getModules()) {
            for (MavenProject mod : getReactorProjects()) {
                try {
                    File mpp = new File(project.getBasedir(), m);
                    mpp = mpp.getCanonicalFile();
                    if (mpp.compareTo(mod.getBasedir()) == 0 && descendants.add(mod)
                            && getLog().isDebugEnabled()) {
                        getLog().debug(String.format("Decendent module %s added", mod.getName()));

                    }
                } catch (IOException ex) {
                    if (getLog().isDebugEnabled()) {
                        getLog().debug("Unable to determine module path", ex);
                    }
                }
            }
        }
        do {
            size = descendants.size();
            for (MavenProject p : getReactorProjects()) {
                if (project.equals(p.getParent()) || descendants.contains(p.getParent())) {
                    if (descendants.add(p) && getLog().isDebugEnabled()) {
                        getLog().debug(String.format("Decendent %s added", p.getName()));

                    }
                    for (MavenProject modTest : getReactorProjects()) {
                        if (p.getModules() != null && p.getModules().contains(modTest.getName())
                                && descendants.add(modTest)
                                && getLog().isDebugEnabled()) {
                            getLog().debug(String.format("Decendent %s added", modTest.getName()));
                        }
                    }
                }
                final Set<MavenProject> addedDescendants = new HashSet<MavenProject>();
                for (MavenProject dec : descendants) {
                    for (String mod : dec.getModules()) {
                        try {
                            File mpp = new File(dec.getBasedir(), mod);
                            mpp = mpp.getCanonicalFile();
                            if (mpp.compareTo(p.getBasedir()) == 0) {
                                addedDescendants.add(p);
                            }
                        } catch (IOException ex) {
                            if (getLog().isDebugEnabled()) {
                                getLog().debug("Unable to determine module path", ex);
                            }
                        }
                    }
                }
                for (MavenProject addedDescendant : addedDescendants) {
                    if (descendants.add(addedDescendant) && getLog().isDebugEnabled()) {
                        getLog().debug(String.format("Decendent module %s added", addedDescendant.getName()));
                    }
                }
            }
        } while (size != 0 && size != descendants.size());
        if (getLog().isDebugEnabled()) {
            getLog().debug(String.format("%s has %d children", project, descendants.size()));
        }
        return descendants;
    }

    /**
     * Test if the project has pom packaging
     *
     * @param mavenProject Project to test
     * @return <code>true</code> if it has a pom packaging; otherwise
     * <code>false</code>
     */
    protected boolean isMultiModule(MavenProject mavenProject) {
        return "pom".equals(mavenProject.getPackaging());
    }

    /**
     * Initializes the engine, runs a scan, and writes the serialized
     * dependencies to disk.
     *
     * @return the MavenEngine used to execute dependency-check
     * @throws MojoExecutionException thrown if there is an exception running
     * the mojo
     * @throws MojoFailureException thrown if dependency-check is configured to
     * fail the build if severe CVEs are identified.
     */
    protected MavenEngine generateDataFile() throws MojoExecutionException, MojoFailureException {
        MavenEngine engine = null;
        try {
            engine = initializeEngine();
        } catch (DatabaseException ex) {
            if (getLog().isDebugEnabled()) {
                getLog().debug("Database connection error", ex);
            }
            final String msg = "An exception occured connecting to the local database. Please see the log file for more details.";
            if (this.isFailOnError()) {
                throw new MojoExecutionException(msg, ex);
            }
            getLog().error(msg, ex);
            return null;
        }
        return generateDataFile(engine, getProject());
    }

    /**
     * Runs dependency-check's MavenEngine and writes the serialized
     * dependencies to disk.
     *
     * @param engine the MavenEngine to use when scanning.
     * @param project the project to scan and generate the data file for
     * @return the MavenEngine used to execute dependency-check
     * @throws MojoExecutionException thrown if there is an exception running
     * the mojo
     * @throws MojoFailureException thrown if dependency-check is configured to
     * fail the build if severe CVEs are identified.
     */
    protected MavenEngine generateDataFile(MavenEngine engine, MavenProject project) throws MojoExecutionException, MojoFailureException {
        if (getLog().isDebugEnabled()) {
            getLog().debug(String.format("Begin Scanning: %s", project.getName()));
        }
        engine.getDependencies().clear();
        engine.resetFileTypeAnalyzers();
        scanArtifacts(project, engine);
        try {
            engine.analyzeDependencies();
        } catch (ExceptionCollection ex) {
            ExceptionCollection col = (ExceptionCollection) engine.getExecutionRoot().getContextValue(AGGREGATE_EXCEPTIONS);
            if (col == null) {
                col = ex;
            } else if (ex.isFatal()) {
                col.setFatal(true);
                col.getExceptions().addAll(ex.getExceptions());
            }
            if (col.isFatal()) {
                final String msg = String.format("Fatal exception(s) analyzing %s", project.getName());
                if (this.isFailOnError()) {
                    throw new MojoExecutionException(msg, ex);
                }
                getLog().error(msg, col);
                return null;
            } else {
                final String msg = String.format("Exception(s) analyzing %s", project.getName());
                if (getLog().isDebugEnabled()) {
                    getLog().debug(msg, ex);
                }
                engine.getExecutionRoot().setContextValue(AGGREGATE_EXCEPTIONS, col);
            }
        }
        final File target = new File(project.getBuild().getDirectory());
        writeDataFile(project, target, engine.getDependencies());
        showSummary(project, engine.getDependencies());
        checkForFailure(engine.getDependencies());
        return engine;
    }

    @Override
    public boolean canGenerateReport() {
        return true; //aggregate always returns true for now - we can look at a more complicated/acurate solution later
    }

    /**
     * The name of the report in the site.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "name", defaultValue = "dependency-check:aggregate", required = true)
    private String name = "dependency-check:aggregate";

    /**
     * Returns the report name.
     *
     * @param locale the location
     * @return the report name
     */
    @Override
    public String getName(Locale locale) {
        return name;
    }

    /**
     * Gets the description of the Dependency-Check report to be displayed in
     * the Maven Generated Reports page.
     *
     * @param locale The Locale to get the description for
     * @return the description
     */
    @Override
    public String getDescription(Locale locale) {
        return "Generates an aggregate report of all child Maven projects providing details on any "
                + "published vulnerabilities within project dependencies. This report is a best "
                + "effort and may contain false positives and false negatives.";
    }
}
