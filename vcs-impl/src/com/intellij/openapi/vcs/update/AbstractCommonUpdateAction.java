/*
 * Copyright (c) 2004 JetBrains s.r.o. All  Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * -Redistributions of source code must retain the above copyright
 *  notice, this list of conditions and the following disclaimer.
 *
 * -Redistribution in binary form must reproduct the above copyright
 *  notice, this list of conditions and the following disclaimer in
 *  the documentation and/or other materials provided with the distribution.
 *
 * Neither the name of JetBrains or IntelliJ IDEA
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING
 * ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. JETBRAINS AND ITS LICENSORS SHALL NOT
 * BE LIABLE FOR ANY DAMAGES OR LIABILITIES SUFFERED BY LICENSEE AS A RESULT
 * OF OR RELATING TO USE, MODIFICATION OR DISTRIBUTION OF THE SOFTWARE OR ITS
 * DERIVATIVES. IN NO EVENT WILL JETBRAINS OR ITS LICENSORS BE LIABLE FOR ANY LOST
 * REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL,
 * INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY
 * OF LIABILITY, ARISING OUT OF THE USE OF OR INABILITY TO USE SOFTWARE, EVEN
 * IF JETBRAINS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 *
 */
package com.intellij.openapi.vcs.update;

import com.intellij.history.Checkpoint;
import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.actions.AbstractVcsAction;
import com.intellij.openapi.vcs.actions.VcsContext;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManagerImpl;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesAdapter;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesCache;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import com.intellij.openapi.vcs.ex.ProjectLevelVcsManagerEx;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.OptionsDialog;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

public abstract class AbstractCommonUpdateAction extends AbstractVcsAction {
  private final static Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.update.AbstractCommonUpdateAction");

  private final ActionInfo myActionInfo;
  private final ScopeInfo myScopeInfo;

  protected AbstractCommonUpdateAction(ActionInfo actionInfo, ScopeInfo scopeInfo) {
    myActionInfo = actionInfo;
    myScopeInfo = scopeInfo;
  }

  private String getCompleteActionName(VcsContext dataContext) {
    return myActionInfo.getActionName(myScopeInfo.getScopeName(dataContext, myActionInfo));
  }

  protected void actionPerformed(final VcsContext context) {
    final Project project = context.getProject();

    boolean showUpdateOptions = myActionInfo.showOptions(project);

    if (project != null) {
      try {
        if (ApplicationManager.getApplication().isDispatchThread()) {
          ApplicationManager.getApplication().saveAll();
        }

        final FilePath[] filePaths = myScopeInfo.getRoots(context, myActionInfo);
        final FilePath[] roots = filterDescindingFiles(filterRoots(filePaths, context), project);
        if (roots.length == 0) {
          return;
        }

        final Map<AbstractVcs, Collection<FilePath>> vcsToVirtualFiles = createVcsToFilesMap(roots, project);

        if (showUpdateOptions || OptionsDialog.shiftIsPressed(context.getModifiers())) {
          showOptionsDialog(vcsToVirtualFiles, project, context);
        }

        for (AbstractVcs vcs : vcsToVirtualFiles.keySet()) {
          final UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
          if ((updateEnvironment != null) && (! updateEnvironment.validateOptions(vcsToVirtualFiles.get(vcs)))) {
            // messages already shown
            return;
          }
        }

        Task.Backgroundable task = new Updater(project, roots, vcsToVirtualFiles);
        ProgressManager.getInstance().run(task);
      }
      catch (ProcessCanceledException e1) {
        //ignore
      }
    }
  }

  private boolean canGroupByChangelist(final Set<AbstractVcs> abstractVcses) {
    if (myActionInfo.canGroupByChangelist()) {
      for(AbstractVcs vcs: abstractVcses) {
        if (vcs.getCachingCommittedChangesProvider() != null) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean someSessionWasCanceled(List<UpdateSession> updateSessions) {
    for (UpdateSession updateSession : updateSessions) {
      if (updateSession.isCanceled()) {
        return true;
      }
    }
    return false;
  }

  private static String getAllFilesAreUpToDateMessage(FilePath[] roots) {
    if (roots.length == 1 && !roots[0].isDirectory()) {
      return VcsBundle.message("message.text.file.is.up.to.date");
    }
    else {
      return VcsBundle.message("message.text.all.files.are.up.to.date");
    }
  }

  private void showOptionsDialog(final Map<AbstractVcs, Collection<FilePath>> updateEnvToVirtualFiles, final Project project,
                                 final VcsContext dataContext) {
    LinkedHashMap<Configurable, AbstractVcs> envToConfMap = createConfigurableToEnvMap(updateEnvToVirtualFiles);
    if (!envToConfMap.isEmpty()) {
      UpdateOrStatusOptionsDialog dialogOrStatus = myActionInfo.createOptionsDialog(project, envToConfMap,
                                                                                    myScopeInfo.getScopeName(dataContext,
                                                                                                             myActionInfo));
      dialogOrStatus.show();
      if (!dialogOrStatus.isOK()) {
        throw new ProcessCanceledException();
      }
    }
  }

  private LinkedHashMap<Configurable, AbstractVcs> createConfigurableToEnvMap(Map<AbstractVcs, Collection<FilePath>> updateEnvToVirtualFiles) {
    LinkedHashMap<Configurable, AbstractVcs> envToConfMap = new LinkedHashMap<Configurable, AbstractVcs>();
    for (AbstractVcs vcs : updateEnvToVirtualFiles.keySet()) {
      Configurable configurable = myActionInfo.getEnvironment(vcs).createConfigurable(updateEnvToVirtualFiles.get(vcs));
      if (configurable != null) {
        envToConfMap.put(configurable, vcs);
      }
    }
    return envToConfMap;
  }

  private Map<AbstractVcs,Collection<FilePath>> createVcsToFilesMap(FilePath[] roots, Project project) {
    HashMap<AbstractVcs, Collection<FilePath>> resultPrep = new HashMap<AbstractVcs, Collection<FilePath>>();

    for (FilePath file : roots) {
      AbstractVcs vcs = VcsUtil.getVcsFor(project, file);
      if (vcs != null) {
        UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
        if (updateEnvironment != null) {
          if (!resultPrep.containsKey(vcs)) resultPrep.put(vcs, new HashSet<FilePath>());
          resultPrep.get(vcs).add(file);
        }
      }
    }

    final Map<AbstractVcs, Collection<FilePath>> result = new HashMap<AbstractVcs, Collection<FilePath>>();
    for (Map.Entry<AbstractVcs, Collection<FilePath>> entry : resultPrep.entrySet()) {
      final AbstractVcs vcs = entry.getKey();
      final List<FilePath> paths = new ArrayList<FilePath>(entry.getValue());
      final List<VirtualFile> files = ObjectsConvertor.convert(paths, ObjectsConvertor.FILEPATH_TO_VIRTUAL, ObjectsConvertor.NOT_NULL);
      result.put(vcs, ObjectsConvertor.vf2fp(vcs.filterUniqueRoots(files)));
    }

    return result;
  }

  private static boolean containsParent(FilePath[] array, FilePath file) {
    for (FilePath virtualFile : array) {
      if (virtualFile == file) continue;
      if (VfsUtil.isAncestor(virtualFile.getIOFile(), file.getIOFile(), false)) return true;
    }
    return false;
  }

  @NotNull
  private FilePath[] filterRoots(FilePath[] roots, VcsContext vcsContext) {
    final ArrayList<FilePath> result = new ArrayList<FilePath>();
    final Project project = vcsContext.getProject();
    for (FilePath file : roots) {
      AbstractVcs vcs = VcsUtil.getVcsFor(project, file);
      if (vcs != null) {
        if (!myScopeInfo.filterExistsInVcs() || AbstractVcs.fileInVcsByFileStatus(project, file)) {
          UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
          if (updateEnvironment != null) {
            result.add(file);
          }
        }
        else {
          final VirtualFile virtualFile = file.getVirtualFile();
          if (virtualFile != null && virtualFile.isDirectory()) {
            final VirtualFile[] vcsRoots = ProjectLevelVcsManager.getInstance(vcsContext.getProject()).getAllVersionedRoots();
            for(VirtualFile vcsRoot: vcsRoots) {
              if (VfsUtil.isAncestor(virtualFile, vcsRoot, false)) {
                result.add(file);
              }
            }
          }
        }
      }
    }
    return result.toArray(new FilePath[result.size()]);
  }

  protected abstract boolean filterRootsBeforeAction();

  protected void update(VcsContext vcsContext, Presentation presentation) {
    Project project = vcsContext.getProject();

    if (project != null) {

      String actionName = getCompleteActionName(vcsContext);
      if (myActionInfo.showOptions(project) || OptionsDialog.shiftIsPressed(vcsContext.getModifiers())) {
        actionName += "...";
      }

      presentation.setText(actionName);

      presentation.setVisible(true);
      presentation.setEnabled(true);

      if (supportingVcsesAreEmpty(vcsContext.getProject(), myActionInfo)) {
        presentation.setVisible(false);
        presentation.setEnabled(false);
      }

      if (filterRootsBeforeAction()) {
        FilePath[] roots = filterRoots(myScopeInfo.getRoots(vcsContext, myActionInfo), vcsContext);
        if (roots.length == 0) {
          presentation.setVisible(false);
          presentation.setEnabled(false);
        }
      }

      if (presentation.isVisible() && presentation.isEnabled() &&
          ProjectLevelVcsManager.getInstance(project).isBackgroundVcsOperationRunning()) {
        presentation.setEnabled(false);
      }
    } else {
      presentation.setVisible(false);
      presentation.setEnabled(false);
    }
 }

  protected boolean forceSyncUpdate(final AnActionEvent e) {
    return true;
  }

  private static boolean supportingVcsesAreEmpty(final Project project, final ActionInfo actionInfo) {
    if (project == null) return true;
    final AbstractVcs[] allActiveVcss = ProjectLevelVcsManager.getInstance(project).getAllActiveVcss();
    for (AbstractVcs activeVcs : allActiveVcss) {
      if (actionInfo.getEnvironment(activeVcs) != null) return false;
    }
    return true;
  }

  private class Updater extends Task.Backgroundable {
    private final Project myProject;
    private final ProjectLevelVcsManagerEx myProjectLevelVcsManager;
    private UpdatedFiles myUpdatedFiles;
    private final FilePath[] myRoots;
    private final Map<AbstractVcs, Collection<FilePath>> myVcsToVirtualFiles;
    private final ArrayList<VcsException> myVcsExceptions;
    private final List<UpdateSession> myUpdateSessions;
    private int myUpdateNumber;

    // vcs name, context object
    private final Map<String, SequentialUpdatesContext> myContextInfo;
    private VcsDirtyScopeManager myDirtyScopeManager;

    private Checkpoint myBefore;
    private Checkpoint myAfter;

    public Updater(final Project project, final FilePath[] roots, final Map<AbstractVcs, Collection<FilePath>> vcsToVirtualFiles) {
      super(project, getTemplatePresentation().getText(), true, VcsConfiguration.getInstance(project).getUpdateOption());
      myProject = project;
      myProjectLevelVcsManager = ProjectLevelVcsManagerEx.getInstanceEx(project);
      myDirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);
      myRoots = roots;
      myVcsToVirtualFiles = vcsToVirtualFiles;

      myUpdatedFiles = UpdatedFiles.create();
      myVcsExceptions = new ArrayList<VcsException>();
      myUpdateSessions = new ArrayList<UpdateSession>();

      // create from outside without any context; context is created by vcses
      myContextInfo = new HashMap<String, SequentialUpdatesContext>();
      myUpdateNumber = 1;
    }

    private void reset() {
      myUpdatedFiles = UpdatedFiles.create();
      myVcsExceptions.clear();
      myUpdateSessions.clear();
      ++ myUpdateNumber;
    }

    private void suspendIfNeeded() {
      if (! myActionInfo.canChangeFileStatus()) {
        // i.e. for update but not for integrate or status
        ((VcsDirtyScopeManagerImpl) myDirtyScopeManager).suspendMe();
      }
    }

    private void releaseIfNeeded() {
      if (! myActionInfo.canChangeFileStatus()) {
        // i.e. for update but not for integrate or status
        ((VcsDirtyScopeManagerImpl) myDirtyScopeManager).reanimate();
      }
    }

    public void run(@NotNull final ProgressIndicator indicator) {
      suspendIfNeeded();
      try {
        runImpl(indicator);
      } catch (Throwable t) {
        releaseIfNeeded();
        if (t instanceof Error) {
          throw ((Error) t);
        } else if (t instanceof RuntimeException) {
          throw ((RuntimeException) t);
        }
        throw new RuntimeException(t);
      }
    }

    private void runImpl(@NotNull final ProgressIndicator indicator) {
      ProjectManagerEx.getInstanceEx().blockReloadingProjectOnExternalChanges();
      myProjectLevelVcsManager.startBackgroundVcsOperation();

      myBefore = LocalHistory.putCheckpoint(myProject);

      ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();

      try {
        int toBeProcessed = myVcsToVirtualFiles.size();
        int processed = 0;
        for (AbstractVcs vcs : myVcsToVirtualFiles.keySet()) {
          final UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
          updateEnvironment.fillGroups(myUpdatedFiles);
          Collection<FilePath> files = myVcsToVirtualFiles.get(vcs);

          final SequentialUpdatesContext context = myContextInfo.get(vcs.getName());
          final Ref<SequentialUpdatesContext> refContext = new Ref<SequentialUpdatesContext>(context);
          UpdateSession updateSession =
            updateEnvironment.updateDirectories(files.toArray(new FilePath[files.size()]), myUpdatedFiles, progressIndicator, refContext);
          myContextInfo.put(vcs.getName(), refContext.get());
          processed++;
          if (progressIndicator != null) {
            progressIndicator.setFraction((double)processed / (double)toBeProcessed);
          }
          myVcsExceptions.addAll(updateSession.getExceptions());
          myUpdateSessions.add(updateSession);
        }
      } finally {
        try {
          if (progressIndicator != null) {
            progressIndicator.setText(VcsBundle.message("progress.text.synchronizing.files"));
            progressIndicator.setText2("");
          }
          doVfsRefresh();
        } finally {
          myAfter = LocalHistory.putCheckpoint(myProject);
          myProjectLevelVcsManager.stopBackgroundVcsOperation();
        }
      }
    }

    private void doVfsRefresh() {
      final String actionName = VcsBundle.message("local.history.update.from.vcs");
      final LocalHistoryAction action = LocalHistory.startAction(myProject, actionName);
      try {
        LOG.info("Calling refresh files after update for roots: " + Arrays.toString(myRoots));
        RefreshVFsSynchronously.updateAllChanged(myUpdatedFiles);
      }
      finally {
        action.finish();
        LocalHistory.putSystemLabel(myProject, actionName);
      }
    }

    @Nullable
    public NotificationInfo getNotificationInfo() {
      StringBuffer text = new StringBuffer();
      final List<FileGroup> groups = myUpdatedFiles.getTopLevelGroups();
      for (FileGroup group : groups) {
        appendGroup(text, group);
      }

      return new NotificationInfo("VCS Update", "VCS Update Finished", text.toString(), true);
    }

    private void appendGroup(final StringBuffer text, final FileGroup group) {
      final int s = group.getFiles().size();
      if (s > 0) {
        if (text.length() > 0) text.append("\n");
        text.append(s + " Files " + group.getUpdateName());
      }

      final List<FileGroup> list = group.getChildren();
      for (FileGroup g : list) {
        appendGroup(text, g);
      }
    }

    public void onSuccess() {
      try {
        onSuccessImpl();
      } finally {
        releaseIfNeeded();
      }
    }

    private void onSuccessImpl() {
      if (myProject.isDisposed()) {
        ProjectManagerEx.getInstanceEx().unblockReloadingProjectOnExternalChanges();
        return;
      }
      boolean continueChain = false;
      for (SequentialUpdatesContext context : myContextInfo.values()) {
        continueChain |= context != null;
      }
      final boolean continueChainFinal = continueChain;

      final boolean someSessionWasCancelled = someSessionWasCanceled(myUpdateSessions);
      if (! someSessionWasCancelled) {
        for (final UpdateSession updateSession : myUpdateSessions) {
          updateSession.onRefreshFilesCompleted();
        }
      }

      if (myActionInfo.canChangeFileStatus()) {
        final List<VirtualFile> files = new ArrayList<VirtualFile>();
        UpdateFilesHelper.iterateFileGroupFiles(myUpdatedFiles, new UpdateFilesHelper.Callback() {
          public void onFile(final String filePath, final String groupId) {
            @NonNls final String path = VfsUtil.pathToUrl(filePath.replace(File.separatorChar, '/'));
            final VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(path);
            if (file != null) {
              files.add(file);
            }
          }
        });
        myDirtyScopeManager.filesDirty(files, null);
      }

      final boolean updateSuccess = (! someSessionWasCancelled) && (myVcsExceptions.isEmpty());

      if (! someSessionWasCancelled) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            if (myProject.isDisposed()) {
              ProjectManagerEx.getInstanceEx().unblockReloadingProjectOnExternalChanges();
              return;
            }
            if (! myVcsExceptions.isEmpty()) {
              if (continueChainFinal) {
                myVcsExceptions.add(contextInterruptedMessages());
              }
              AbstractVcsHelper.getInstance(myProject).showErrors(myVcsExceptions, VcsBundle.message("message.title.vcs.update.errors",
                                                                                                     getTemplatePresentation().getText()));
            }
            else {
              final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
              if (indicator != null) {
                indicator.setText(VcsBundle.message("progress.text.updating.done"));
              }
            }

            final boolean noMerged = myUpdatedFiles.getGroupById(FileGroup.MERGED_WITH_CONFLICT_ID).isEmpty();
            if (myUpdatedFiles.isEmpty() && myVcsExceptions.isEmpty()) {
              ToolWindowManager.getInstance(myProject).notifyByBalloon(
                ChangesViewContentManager.TOOLWINDOW_ID, MessageType.INFO, getAllFilesAreUpToDateMessage(myRoots));
            }
            else if (! myUpdatedFiles.isEmpty()) {
              showUpdateTree(continueChainFinal && updateSuccess && noMerged);

              final CommittedChangesCache cache = CommittedChangesCache.getInstance(myProject);
              cache.processUpdatedFiles(myUpdatedFiles);
            }

            ProjectManagerEx.getInstanceEx().unblockReloadingProjectOnExternalChanges();

            if (continueChainFinal && updateSuccess) {
              if (!noMerged) {
                showContextInterruptedError();
              } else {
                // trigger next update; for CVS when updating from several branvhes simultaneously
                reset();
                ProgressManager.getInstance().run(Updater.this);
              }
            }
          }
        });
      } else if (continueChain) {
        // since error
        showContextInterruptedError();
        ProjectManagerEx.getInstanceEx().unblockReloadingProjectOnExternalChanges();
      }
    }

    private void showContextInterruptedError() {
      AbstractVcsHelper.getInstance(myProject).showErrors(Collections.singletonList(contextInterruptedMessages()),
                                    VcsBundle.message("message.title.vcs.update.errors", getTemplatePresentation().getText()));
    }

    @NotNull
    private VcsException contextInterruptedMessages() {
      final List<String> strings = new ArrayList<String>();
      strings.add("Update operation not completed:");
      for (Map.Entry<String, SequentialUpdatesContext> entry : myContextInfo.entrySet()) {
        final SequentialUpdatesContext context = entry.getValue();
        if (context != null) {
          strings.add(context.getMessageWhenInterruptedBeforeStart());
        }
      }
      final VcsException vcsException = new VcsException(strings);
      vcsException.setIsWarning(true);
      return vcsException;
    }

    private void showUpdateTree(final boolean willBeContinued) {
      RestoreUpdateTree restoreUpdateTree = RestoreUpdateTree.getInstance(myProject);
      restoreUpdateTree.registerUpdateInformation(myUpdatedFiles, myActionInfo);
      final String text = getTemplatePresentation().getText() + ((willBeContinued || (myUpdateNumber > 1)) ? ("#" + myUpdateNumber) : "");
      final UpdateInfoTree updateInfoTree = myProjectLevelVcsManager.showUpdateProjectInfo(myUpdatedFiles, text, myActionInfo);

      updateInfoTree.setBefore(myBefore);
      updateInfoTree.setAfter(myAfter);
      
      // todo make temporal listener of changes reload
      if (updateInfoTree != null) {
        updateInfoTree.setCanGroupByChangeList(canGroupByChangelist(myVcsToVirtualFiles.keySet()));
        final MessageBusConnection messageBusConnection = myProject.getMessageBus().connect();
        messageBusConnection.subscribe(CommittedChangesCache.COMMITTED_TOPIC, new CommittedChangesAdapter() {
          public void incomingChangesUpdated(final List<CommittedChangeList> receivedChanges) {
            if (receivedChanges != null) {
              ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                  updateInfoTree.setChangeLists(receivedChanges);
                }
              }, myProject.getDisposed());
              messageBusConnection.disconnect();
            }
          }
        });
      }
    }

    public void onCancel() {
      onSuccess();
    }
  }
}
