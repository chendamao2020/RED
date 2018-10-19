/*
 * Copyright 2015 Nokia Solutions and Networks
 * Licensed under the Apache License, Version 2.0,
 * see license.txt file for details.
 */
package org.robotframework.ide.eclipse.main.plugin.navigator.actions;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.robotframework.ide.eclipse.main.plugin.launch.local.RobotLaunchConfiguration;
import org.robotframework.ide.eclipse.main.plugin.launch.local.RobotLaunchConfigurationFinder;
import org.robotframework.ide.eclipse.main.plugin.model.RobotCase;
import org.robotframework.ide.eclipse.main.plugin.model.RobotCasesSection;
import org.robotframework.ide.eclipse.main.plugin.model.RobotCodeHoldingElement;
import org.robotframework.ide.eclipse.main.plugin.model.RobotFileInternalElement;
import org.robotframework.ide.eclipse.main.plugin.model.RobotSuiteFile;
import org.robotframework.ide.eclipse.main.plugin.model.RobotSuiteFileSection;
import org.robotframework.ide.eclipse.main.plugin.model.RobotTask;
import org.robotframework.ide.eclipse.main.plugin.model.RobotTasksSection;
import org.robotframework.ide.eclipse.main.plugin.propertytester.SelectionsPropertyTester;
import org.robotframework.red.viewers.Selections;

public class RunSelectedTestCasesAction extends Action implements IEnablementUpdatingAction {

    private final ISelectionProvider selectionProvider;

    private final Mode mode;

    public RunSelectedTestCasesAction(final ISelectionProvider selectionProvider, final Mode mode) {
        super(mode.actionName, mode.getImage());
        this.selectionProvider = selectionProvider;
        this.mode = mode;
    }

    @Override
    public void run() {
        runSelectedTestCases((IStructuredSelection) selectionProvider.getSelection(), mode);
    }

    public static void runSelectedTestCases(final IStructuredSelection selection, final Mode mode) {
        final WorkspaceJob job = new WorkspaceJob("Launching Robot Tests") {

            @Override
            public IStatus runInWorkspace(final IProgressMonitor monitor) throws CoreException {
                final List<RobotCase> selectedTestCases = Selections.getElements(selection, RobotCase.class);
                final Map<IResource, List<String>> resourcesToTestCases = groupTestCasesByResource(selectedTestCases);

                if (!resourcesToTestCases.isEmpty()) {
                    final ILaunchConfigurationWorkingCopy config = RobotLaunchConfigurationFinder
                            .getLaunchConfigurationForSelectedTestCases(resourcesToTestCases);

                    final RobotLaunchConfiguration robotconfig = new RobotLaunchConfiguration(config);
                    robotconfig.updateTestCases(resourcesToTestCases);

                    config.launch(mode.launchMgrName, monitor);
                    return Status.OK_STATUS;
                }

                final List<RobotTask> selectedTasks = Selections.getElements(selection, RobotTask.class);
                final Map<IResource, List<String>> resourcesToTasks = groupTestCasesByResource(selectedTasks);
                if (!resourcesToTasks.isEmpty()) {
                    final ILaunchConfigurationWorkingCopy config = RobotLaunchConfigurationFinder
                            .getLaunchConfigurationForSelectedTestCases(resourcesToTasks);

                    final RobotLaunchConfiguration robotconfig = new RobotLaunchConfiguration(config);
                    robotconfig.updateTestCases(resourcesToTasks);

                    config.launch(mode.launchMgrName, monitor);
                    return Status.OK_STATUS;
                }

                final List<RobotCasesSection> selectedTestSuites = Selections.getElements(selection,
                        RobotCasesSection.class);
                final List<IResource> resources1 = findResourcesForSections(selectedTestSuites);
                if (!resources1.isEmpty()) {
                    RobotLaunchConfigurationFinder.getLaunchConfigurationExceptSelectedTestCases(resources1)
                            .launch(mode.launchMgrName, monitor);
                    return Status.OK_STATUS;
                }

                final List<RobotTasksSection> selectedTaskSuites = Selections.getElements(selection,
                        RobotTasksSection.class);
                final List<IResource> resources2 = findResourcesForSections(selectedTaskSuites);
                if (!resources2.isEmpty()) {
                    RobotLaunchConfigurationFinder.getLaunchConfigurationExceptSelectedTestCases(resources2)
                            .launch(mode.launchMgrName, monitor);
                    return Status.OK_STATUS;
                }
                return Status.OK_STATUS;
            }

            private Map<IResource, List<String>> groupTestCasesByResource(
                    final List<? extends RobotCodeHoldingElement<?>> robotCases) {

                return robotCases.stream()
                        .collect(groupingBy(testCase -> testCase.getSuiteFile().getFile(), LinkedHashMap::new,
                                mapping(RobotCodeHoldingElement::getName, toList())));
            }

            private List<IResource> findResourcesForSections(
                    final List<? extends RobotSuiteFileSection> robotCasesSections) {

                return robotCasesSections.stream()
                        .map(RobotFileInternalElement::getSuiteFile)
                        .map(RobotSuiteFile::getFile)
                        .distinct()
                        .collect(toList());
            }
        };
        job.setUser(false);
        job.schedule();
    }

    @Override
    public void updateEnablement(final IStructuredSelection selection) {
        if (SelectionsPropertyTester.allElementsAreFromSameProject(selection)) {
            final boolean robotCaseAbsent = Selections.getElements(selection, RobotCase.class).isEmpty();
            final boolean robotCasesSectionAbsent = Selections.getElements(selection, RobotCasesSection.class)
                    .isEmpty();
            final boolean robotTaskAbsent = Selections.getElements(selection, RobotTask.class).isEmpty();
            final boolean robotTasksSectionAbsent = Selections.getElements(selection, RobotTasksSection.class)
                    .isEmpty();
            setEnabled(!robotCaseAbsent || !robotCasesSectionAbsent || !robotTaskAbsent || !robotTasksSectionAbsent);
        } else {
            setEnabled(false);
        }
    }

    public static enum Mode {
        RUN("Run", ILaunchManager.RUN_MODE, IDebugUIConstants.IMG_ACT_RUN),
        DEBUG("Debug", ILaunchManager.DEBUG_MODE, IDebugUIConstants.IMG_ACT_DEBUG);

        private final String actionName;

        private final String launchMgrName;

        private final String imageConst;

        private Mode(final String actionName, final String launchMgrName, final String imageConst) {
            this.actionName = actionName;
            this.launchMgrName = launchMgrName;
            this.imageConst = imageConst;
        }

        ImageDescriptor getImage() {
            return DebugUITools.getImageDescriptor(imageConst);
        }
    }
}
