/*
 * Copyright 2018 Nokia Solutions and Networks
 * Licensed under the Apache License, Version 2.0,
 * see license.txt file for details.
 */
package org.robotframework.ide.eclipse.main.plugin.launch.local;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static org.robotframework.ide.eclipse.main.plugin.RedPlugin.newCoreException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.variables.IStringVariableManager;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.rf.ide.core.executor.EnvironmentSearchPaths;
import org.rf.ide.core.executor.RobotRuntimeEnvironment;
import org.rf.ide.core.executor.RunCommandLineCallBuilder;
import org.rf.ide.core.executor.RunCommandLineCallBuilder.IRunCommandLineBuilder;
import org.rf.ide.core.executor.RunCommandLineCallBuilder.RunCommandLine;
import org.rf.ide.core.executor.SuiteExecutor;
import org.rf.ide.core.project.RobotProjectConfig;
import org.robotframework.ide.eclipse.main.plugin.RedPreferences;
import org.robotframework.ide.eclipse.main.plugin.model.RobotProject;
import org.robotframework.ide.eclipse.main.plugin.project.ASuiteFileDescriber;
import org.robotframework.ide.eclipse.main.plugin.project.RedEclipseProjectConfig;

import com.google.common.collect.Maps;

class LocalProcessCommandLineBuilder {

    private final RobotLaunchConfiguration robotConfig;

    private final RobotProject robotProject;

    LocalProcessCommandLineBuilder(final RobotLaunchConfiguration robotConfig, final RobotProject robotProject) {
        this.robotConfig = robotConfig;
        this.robotProject = robotProject;
    }

    RunCommandLine createRunCommandLine(final int port, final RedPreferences preferences)
            throws CoreException, IOException {
        final IRunCommandLineBuilder builder = createBuilder(port);
        addArgumentEntries(builder, preferences);
        addProjectConfigEntries(builder);
        addTags(builder);
        addDataSources(builder, preferences);
        return builder.build();
    }

    private IRunCommandLineBuilder createBuilder(final int port) throws CoreException {
        if (robotConfig.isUsingInterpreterFromProject()) {
            final RobotRuntimeEnvironment runtimeEnvironment = robotProject.getRuntimeEnvironment();
            if (runtimeEnvironment != null) {
                return RunCommandLineCallBuilder.forEnvironment(runtimeEnvironment, port);
            } else {
                return RunCommandLineCallBuilder.forExecutor(SuiteExecutor.Python, port);
            }
        } else {
            return RunCommandLineCallBuilder.forExecutor(robotConfig.getInterpreter(), port);
        }
    }

    private void addArgumentEntries(final IRunCommandLineBuilder builder, final RedPreferences preferences)
            throws CoreException {
        builder.useArgumentFile(preferences.shouldLaunchUsingArgumentsFile());
        if (!robotConfig.getExecutableFilePath().isEmpty()) {
            builder.withExecutableFile(resolveExecutableFile(robotConfig.getExecutableFilePath()));
            builder.addUserArgumentsForExecutableFile(parseArguments(robotConfig.getExecutableFileArguments()));
            builder.useSingleRobotCommandLineArg(preferences.shouldUseSingleCommandLineArgument());
        }
        builder.addUserArgumentsForInterpreter(parseArguments(robotConfig.getInterpreterArguments()));
        builder.addUserArgumentsForRobot(parseArguments(robotConfig.getRobotArguments()));
    }

    private void addProjectConfigEntries(final IRunCommandLineBuilder builder) {
        final RobotProjectConfig projectConfig = robotProject.getRobotProjectConfig();
        if (projectConfig != null) {
            final RedEclipseProjectConfig redConfig = new RedEclipseProjectConfig(robotProject.getProject(),
                    projectConfig);
            final EnvironmentSearchPaths searchPaths = redConfig.createExecutionEnvironmentSearchPaths();
            builder.addLocationsToClassPath(searchPaths.getClassPaths());
            builder.addLocationsToPythonPath(searchPaths.getPythonPaths());
            builder.addVariableFiles(redConfig.getVariableFilePaths());
        }
    }

    private void addTags(final IRunCommandLineBuilder builder) throws CoreException {
        if (robotConfig.isIncludeTagsEnabled()) {
            builder.includeTags(robotConfig.getIncludedTags());
        }
        if (robotConfig.isExcludeTagsEnabled()) {
            builder.excludeTags(robotConfig.getExcludedTags());
        }
    }

    private void addDataSources(final IRunCommandLineBuilder builder, final RedPreferences preferences)
            throws CoreException {
        final Map<IResource, List<String>> resources = findResources(robotProject.getProject(),
                robotConfig.getSuitePaths());
        if (shouldUseSingleTestPathInCommandLine(resources.keySet(), preferences)) {
            builder.withDataSources(newArrayList(getOnlyElement(resources.keySet()).getLocation().toFile()));
            final Function<IResource, List<String>> mapper = r -> newArrayList(
                    r.getLocation().removeFileExtension().lastSegment());
            builder.testsToRun(RobotPathsNaming.createTestNames(resources, "", mapper));
        } else {
            final Map<IResource, List<String>> linkedResources = findLinkedResources(resources);
            final Map<IResource, List<String>> notLinkedResources = Maps.filterKeys(resources,
                    resource -> !resource.isVirtual() && !resource.isLinked(IResource.CHECK_ANCESTORS));

            final List<IResource> dataSources = new ArrayList<>();
            dataSources.add(robotProject.getProject());
            dataSources.addAll(linkedResources.keySet());
            builder.withDataSources(
                    dataSources.stream().map(IResource::getLocation).map(IPath::toFile).collect(toList()));

            final String topLevelSuiteName = RobotPathsNaming.createTopLevelSuiteName(dataSources);
            final Function<IResource, List<String>> mapper = r -> r.isLinked(IResource.CHECK_ANCESTORS)
                    ? newArrayList(r.getLocation().removeFileExtension().lastSegment())
                    : newArrayList(r.getFullPath().removeFileExtension().segments());
            builder.suitesToRun(RobotPathsNaming.createSuiteNames(notLinkedResources, topLevelSuiteName, mapper));
            builder.suitesToRun(RobotPathsNaming.createSuiteNames(linkedResources, topLevelSuiteName, mapper));
            builder.testsToRun(RobotPathsNaming.createTestNames(notLinkedResources, topLevelSuiteName, mapper));
            builder.testsToRun(RobotPathsNaming.createTestNames(linkedResources, topLevelSuiteName, mapper));
        }
    }

    private static File resolveExecutableFile(final String path) throws CoreException {
        final IStringVariableManager variableManager = VariablesPlugin.getDefault().getStringVariableManager();
        final File executableFile = new File(variableManager.performStringSubstitution(path));
        if (!executableFile.exists()) {
            throw newCoreException("Executable file '" + executableFile.getAbsolutePath() + "' does not exist");
        }
        return executableFile;
    }

    private static List<String> parseArguments(final String arguments) {
        final IStringVariableManager variableManager = VariablesPlugin.getDefault().getStringVariableManager();
        return Stream.of(DebugPlugin.parseArguments(arguments)).map(argument -> {
            try {
                return variableManager.performStringSubstitution(argument);
            } catch (final CoreException e) {
                return argument;
            }
        }).collect(toList());
    }

    private static Map<IResource, List<String>> findResources(final IProject project,
            final Map<String, List<String>> suitePaths) throws CoreException {
        final Map<IResource, List<String>> result = new LinkedHashMap<>();
        if (suitePaths.isEmpty()) {
            result.put(project, new ArrayList<>());
            return result;
        }

        final Set<String> problems = new HashSet<>();

        final Map<String, List<String>> sortedSuitePaths = new TreeMap<>(suitePaths);
        for (final Entry<String, List<String>> entry : sortedSuitePaths.entrySet()) {
            final IResource resource = project.findMember(Path.fromPortableString(entry.getKey()));
            if (resource != null) {
                if (resource.isVirtual() || isNotIncluded(resource, result.keySet())) {
                    result.put(resource, entry.getValue());
                }
            } else {
                problems.add("Suite '" + entry.getKey() + "' does not exist in project '" + project.getName() + "'");
            }
        }

        if (!problems.isEmpty()) {
            throw newCoreException(String.join("\n", problems));
        }

        return result;
    }

    private static Map<IResource, List<String>> findLinkedResources(final Map<IResource, List<String>> resourcesMapping)
            throws CoreException {
        final Map<IResource, List<String>> result = new LinkedHashMap<>();

        for (final Entry<IResource, List<String>> entry : resourcesMapping.entrySet()) {
            entry.getKey().accept(r -> {
                if (r.isLinked(IResource.CHECK_ANCESTORS) && isNotIncluded(r, result.keySet()) && isDataSource(r)) {
                    result.put(r, entry.getValue());
                }
                return true;
            });
        }

        return result;
    }

    private static boolean shouldUseSingleTestPathInCommandLine(final Collection<IResource> resources,
            final RedPreferences preferences) throws CoreException {
        return preferences.shouldUseSingleFileDataSource() && resources.size() == 1
                && getOnlyElement(resources) instanceof IFile;
    }

    private static boolean isDataSource(final IResource resource) {
        return resource.getType() != IResource.FILE || ASuiteFileDescriber.isSuiteFile((IFile) resource)
                || ASuiteFileDescriber.isRpaSuiteFile((IFile) resource);
    }

    private static boolean isNotIncluded(final IResource resource, final Set<IResource> resources) {
        return !resource.isVirtual() && resources.stream()
                .noneMatch(r -> !r.isVirtual() && r.getLocation().isPrefixOf(resource.getLocation()));
    }

}
