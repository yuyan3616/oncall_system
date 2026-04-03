package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FileUploadRes {

    private String fileName;
    private String filePath;
    private Long fileSize;
    private String taskId;
    private String ingestionStatus;

    public FileUploadRes() {
    }

    public FileUploadRes(String fileName, String filePath, Long fileSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
    }

    public FileUploadRes(String fileName,
                         String filePath,
                         Long fileSize,
                         String taskId,
                         String ingestionStatus) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.taskId = taskId;
        this.ingestionStatus = ingestionStatus;
    }

}
