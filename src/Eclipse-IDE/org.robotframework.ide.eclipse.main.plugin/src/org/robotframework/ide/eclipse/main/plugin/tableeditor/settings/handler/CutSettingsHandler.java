/*
 * Copyright 2015 Nokia Solutions and Networks
 * Licensed under the Apache License, Version 2.0,
 * see license.txt file for details.
 */
package org.robotframework.ide.eclipse.main.plugin.tableeditor.settings.handler;

import java.util.List;

import javax.inject.Named;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.Transfer;
import org.robotframework.ide.eclipse.main.plugin.model.RobotKeywordCall;
import org.robotframework.ide.eclipse.main.plugin.model.RobotSetting;
import org.robotframework.ide.eclipse.main.plugin.model.cmd.DeleteSettingKeywordCallCommand;
import org.robotframework.ide.eclipse.main.plugin.tableeditor.RobotEditorCommandsStack;
import org.robotframework.ide.eclipse.main.plugin.tableeditor.dnd.KeywordCallsTransfer;
import org.robotframework.ide.eclipse.main.plugin.tableeditor.settings.handler.CutSettingsHandler.E4CutSettingsHandler;
import org.robotframework.red.commands.DIParameterizedHandler;
import org.robotframework.red.viewers.Selections;

public class CutSettingsHandler extends DIParameterizedHandler<E4CutSettingsHandler> {

    public CutSettingsHandler() {
        super(E4CutSettingsHandler.class);
    }

    public static class E4CutSettingsHandler {

        @Execute
        public Object cutKeywords(final RobotEditorCommandsStack commandsStack,
                @Named(Selections.SELECTION) final IStructuredSelection selection, final Clipboard clipboard) {

            final List<RobotSetting> settings = Selections.getElements(selection, RobotSetting.class);
            if (!settings.isEmpty()) {
                clipboard.setContents(
                        new RobotKeywordCall[][] { settings.toArray(new RobotKeywordCall[settings.size()]) },
                        new Transfer[] { KeywordCallsTransfer.getInstance() });

                commandsStack.execute(new DeleteSettingKeywordCallCommand(settings));
            }
            return null;
        }
    }
}
