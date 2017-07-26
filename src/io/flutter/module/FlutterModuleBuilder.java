/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.module;

import com.intellij.execution.OutputListener;
import com.intellij.execution.process.ProcessListener;
import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.*;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import icons.FlutterIcons;
import io.flutter.FlutterBundle;
import io.flutter.FlutterConstants;
import io.flutter.FlutterUtils;
import io.flutter.pub.PubRoot;
import io.flutter.sdk.FlutterSdk;
import io.flutter.sdk.FlutterSdkUtil;
import io.flutter.utils.FlutterModuleUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Arrays.asList;

public class FlutterModuleBuilder extends ModuleBuilder {
  private static final Logger LOG = Logger.getInstance(FlutterModuleBuilder.class);

  private static final String DART_GROUP_NAME = "Static Web"; // == WebModuleBuilder.GROUP_NAME

  private FlutterModuleWizardStep myStep;

  @Override
  public String getName() {
    return getPresentableName();
  }

  @Override
  public String getPresentableName() {
    return FlutterBundle.message("flutter.module.name");
  }

  @Override
  public String getDescription() {
    return FlutterBundle.message("flutter.project.description");
  }

  // This method does not exist in 2017.2.
  @SuppressWarnings({"override", "SameReturnValue"})
  public Icon getBigIcon() {
    return FlutterIcons.Flutter_2x;
  }

  @Override
  public Icon getNodeIcon() {
    return FlutterIcons.Flutter;
  }

  @Override
  public void setupRootModel(ModifiableRootModel model) throws ConfigurationException {
    doAddContentEntry(model);
    // Add a reference to Dart SDK project library, without committing.
    model.addInvalidLibrary("Dart SDK", "project");
  }

  @Nullable
  @Override
  public Module commitModule(@NotNull Project project, @Nullable ModifiableModuleModel model) {
    final String basePath = getModuleFileDirectory();
    if (basePath == null) {
      Messages.showErrorDialog("Module path not set", "Internal Error");
      return null;
    }
    final VirtualFile baseDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath);
    if (baseDir == null) {
      Messages.showErrorDialog("Unable to determine Flutter project directory", "Internal Error");
      return null;
    }

    final FlutterSdk sdk = myStep.getFlutterSdk();
    if (sdk == null) {
      Messages.showErrorDialog("Flutter SDK not found", "Error");
      return null;
    }

    final OutputListener listener = new OutputListener();
    final PubRoot root = runFlutterCreateWithProgress(baseDir, sdk, project, listener);
    if (root == null) {
      final String stderr = listener.getOutput().getStderr();
      final String msg = stderr.isEmpty() ? "Flutter create command was unsuccessful" : stderr;
      Messages.showErrorDialog(msg, "Error");
      return null;
    }
    FlutterSdkUtil.updateKnownSdkPaths(sdk.getHomePath());

    // Create the Flutter module. This indirectly calls setupRootModule, etc.
    final Module flutter = super.commitModule(project, model);
    if (flutter == null) {
      return null;
    }

    FlutterModuleUtils.autoShowMain(project, root);

    addAndroidModule(project, model, basePath, flutter.getName());
    return flutter;
  }

  private void addAndroidModule(@NotNull Project project,
                                @Nullable ModifiableModuleModel model,
                                @NotNull String baseDirPath,
                                @NotNull String flutterModuleName) {
    final VirtualFile baseDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(baseDirPath);
    if (baseDir == null) {
      return;
    }

    final VirtualFile androidFile = findAndroidModuleFile(baseDir, flutterModuleName);
    if (androidFile == null) return;

    try {
      final ModifiableModuleModel toCommit;
      if (model == null) {
        toCommit = ModuleManager.getInstance(project).getModifiableModel();
        model = toCommit;
      }
      else {
        toCommit = null;
      }

      model.loadModule(androidFile.getPath());

      if (toCommit != null) {
        WriteAction.run(toCommit::commit);
      }
    }
    catch (ModuleWithNameAlreadyExists | IOException e) {
      LOG.warn(e);
    }
  }

  @Nullable
  private VirtualFile findAndroidModuleFile(@NotNull VirtualFile baseDir, String flutterModuleName) {
    baseDir.refresh(false, false);
    for (String name : asList(flutterModuleName + "_android.iml", "android.iml")) {
      final VirtualFile candidate = baseDir.findChild(name);
      if (candidate != null && candidate.exists()) {
        return candidate;
      }
    }
    return null;
  }

  @Override
  public boolean validate(Project current, Project dest) {
    return myStep.getFlutterSdk() != null;
  }

  @Override
  public boolean validateModuleName(@NotNull String moduleName) throws ConfigurationException {

    // See: https://www.dartlang.org/tools/pub/pubspec#name

    if (!FlutterUtils.isValidPackageName(moduleName)) {
      throw new ConfigurationException(
        "Invalid module name: '" + moduleName + "' - must be a valid Dart package name (lower_case_with_underscores).");
    }

    if (FlutterUtils.isDartKeword(moduleName)) {
      throw new ConfigurationException("Invalid module name: '" + moduleName + "' - must not be a Dart keyword.");
    }

    if (!FlutterUtils.isValidDartIdentifier(moduleName)) {
      throw new ConfigurationException("Invalid module name: '" + moduleName + "' - must be a valid Dart identifier.");
    }

    if (FlutterConstants.FLUTTER_PACKAGE_DEPENDENCIES.contains(moduleName)) {
      throw new ConfigurationException("Invalid module name: '" + moduleName + "' - this will conflict with Flutter package dependencies.");
    }

    if (moduleName.length() > FlutterConstants.MAX_MODULE_NAME_LENGTH) {
      throw new ConfigurationException("Invalid module name - must be less than " +
                                       FlutterConstants.MAX_MODULE_NAME_LENGTH +
                                       " characters.");
    }

    return super.validateModuleName(moduleName);
  }

  @Nullable
  @Override
  public ModuleWizardStep getCustomOptionsStep(final WizardContext context, final Disposable parentDisposable) {
    myStep = new FlutterModuleWizardStep(context);
    Disposer.register(parentDisposable, myStep);
    return myStep;
  }

  @Override
  public String getParentGroup() {
    return DART_GROUP_NAME;
  }

  @Override
  @NotNull
  public String getBuilderId() {
    // The builder id is used to distinguish between different builders with the same module type, see
    // com.intellij.ide.projectWizard.ProjectTypeStep for an example.
    return StringUtil.notNullize(super.getBuilderId()) + "_" + FlutterModuleBuilder.class.getCanonicalName();
  }

  @Override
  @NotNull
  public ModuleType getModuleType() {
    return ModuleTypeManager.getInstance().findByID(FlutterModuleUtils.getModuleTypeIDForFlutter());
  }

  /**
   * Runs flutter create without showing a console, but with an indeterminate progress dialog.
   * <p>
   * Returns the PubRoot if successful.
   */
  @Nullable
  private static PubRoot runFlutterCreateWithProgress(@NotNull VirtualFile baseDir,
                                                      @NotNull FlutterSdk sdk,
                                                      @NotNull Project project,
                                                      @Nullable ProcessListener processListener) {
    final ProgressManager progress = ProgressManager.getInstance();
    final AtomicReference<PubRoot> result = new AtomicReference<>(null);

    progress.runProcessWithProgressSynchronously(() -> {
      progress.getProgressIndicator().setIndeterminate(true);
      result.set(sdk.createFiles(baseDir, null, processListener));
    }, "Creating Flutter Project", false, project);

    return result.get();
  }

  private static class FlutterModuleWizardStep extends ModuleWizardStep implements Disposable {
    private final FlutterGeneratorPeer myPeer;
    private FlutterSdk myFlutterSdk;

    public FlutterModuleWizardStep(@NotNull WizardContext context) {
      //TODO(pq): find a way to listen to wizard cancelation and propogate to peer.
      myPeer = new FlutterGeneratorPeer(context);
    }

    @Override
    public JComponent getComponent() {
      return myPeer.getComponent();
    }

    @Override
    public void updateDataModel() {
    }

    @Override
    public boolean validate() throws ConfigurationException {
      final boolean valid = myPeer.validate();
      if (valid) {
        myPeer.apply();
      }
      return valid;
    }

    @Override
    public void dispose() {
    }

    @Nullable
    private FlutterSdk getFlutterSdk() {
      final String sdkPath = myPeer.getSdkComboPath();

      //Ensure the local filesystem has caught up to external processes (e.g., git clone).
      if (!sdkPath.isEmpty()) {
        try {
          LocalFileSystem
            .getInstance().refreshAndFindFileByPath(sdkPath);
        }
        catch (Throwable e) {
          // It's possible that the refresh will fail in which case we just want to trap and ignore.
        }
      }
      return FlutterSdk.forPath(sdkPath);
    }
  }
}
