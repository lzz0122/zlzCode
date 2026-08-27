package com.zlzcode.agent.workspace.picker;

import org.springframework.stereotype.Service;

import javax.swing.JFileChooser;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Path;

@Service
public class DirectoryPickerService {

    public Path pick() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new DirectoryPickerUnavailableException("当前环境没有可用的目录选择器");
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 CodeAgent 项目目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        int result = chooser.showOpenDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) return null;
        File selected = chooser.getSelectedFile();
        return selected == null ? null : selected.toPath();
    }
}
