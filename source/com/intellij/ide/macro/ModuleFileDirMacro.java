package com.intellij.ide.macro;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.DataAccessor;

import java.io.File;

public final class ModuleFileDirMacro extends Macro {
  public String getName() {
    return "ModuleFileDir";
  }

  public String getDescription() {
    return IdeBundle.message("macro.module.file.directory");
  }

  public String expand(DataContext dataContext) {
    final String path = DataAccessor.MODULE_FILE_PATH.from(dataContext);
    if (path == null) {
      return null;
    }
    final File fileDir = new File(path).getParentFile();
    if (fileDir == null) {
      return null;
    }
    return fileDir.getPath();
  }
}