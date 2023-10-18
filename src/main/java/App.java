import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class App {

    private static final int PER_MB = 1024 * 1024;

    private static final int MB_SIZE = 60;

    private static final int PER_READ_SIZE = 1024;

    private static final String PART_FILE_SUFFIX = ".fsp";

    public static void main(String[] args) throws IOException {

        if (args.length < 3) {
            System.out.println("please input mode, input dir and output dir");
            return;
        }

        if ("1".equals(args[0])) {
            packageFile(args[1], args[2]);
        } else if ("2".equals(args[0])) {
            unpackageFile(args[1], args[2]);
        } else {
            System.out.println("mode must be 1 or 2");
            return;
        }

    }

    static byte[] intToBytes(int n) {
        boolean pos = n >= 0;
        if (!pos) {
            n = -n;
        }
        byte[] bytes = new byte[5];
        bytes[4] = (byte) (n & 0xff);
        n = n >> 8;
        bytes[3] = (byte) (n & 0xff);
        n = n >> 8;
        bytes[2] = (byte) (n & 0xff);
        n = n >> 8;
        bytes[1] = (byte) (n & 0xff);
        bytes[0] = (byte) (pos ? 0 : 1);
        return bytes;
    }

    static int bytesToInt(byte[] bytes) {
        int n = 0;
        n = bytes[1] & 0xff;
        n = (n << 8) | (bytes[2] & 0xff);
        n = (n << 8) | (bytes[3] & 0xff);
        n = (n << 8) | (bytes[4] & 0xff);
        if (bytes[0] == 1) {
            n = -n;
        }
        return n;
    }

    static Map<String, File> getAllFiles(File inputFile) {
        if (inputFile.isFile()) {
            return getAllFiles(null, inputFile);
        }
        Map<String, File> allFiles = new HashMap<>();
        File[] files = inputFile.listFiles();
        if (files != null) {
            for (File file : files) {
                Map<String, File> subFiles = getAllFiles(null, file);
                allFiles.putAll(subFiles);
            }
        }

        return allFiles;
    }

    private static String fileNameEscape(String fileName) {
        String[] split = fileName.split("/");
        return String.join("\\/", split);
    }

    private static List<String> toHierarchyNames(String escapeFileName) {
        List<String> list = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        char[] charArray = escapeFileName.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            if (charArray[i] == '\\') {
                if (i < charArray.length - 1 && charArray[i + 1] == '/') {
                    sb.append('/');
                    i++;
                    continue;
                }
            }
            if (charArray[i] == '/') {
                list.add(sb.toString());
                sb = new StringBuilder();
                continue;
            }
            sb.append(charArray[i]);
        }
        if (sb.length()> 0) {
            list.add(sb.toString());
        }
        return list;
    }

    static Map<String, File> getAllFiles(String prefix, File inputFile) {
        Map<String, File> allFile = new HashMap<>();
        if (inputFile.isFile()) {
            String fileName = fileNameEscape(inputFile.getName());
            String key = prefix != null ? prefix + "/" + fileName : fileName;
            allFile.put(key, inputFile);
            return allFile;
        }

        String fileName = fileNameEscape(inputFile.getName());
        prefix = prefix != null ? prefix + "/" + fileName : fileName;

        File[] files = inputFile.listFiles();
        if (files != null) {
            for (File file : files) {
                Map<String, File> subFiles = getAllFiles(prefix, file);
                allFile.putAll(subFiles);
            }
        }

        return allFile;
    }


    public static void packageFile(String inputDir, String outputDir) throws IOException {
        String userBaseDir = System.getProperty("user.dir");

        File inputFile;
        if (!inputDir.startsWith("/")) {
            inputFile = new File(userBaseDir, inputDir);
        } else {
            inputFile = new File(inputDir);
        }

        File outputFile;
        if (!outputDir.startsWith("/")) {
            outputFile = new File(userBaseDir, outputDir);
        } else {
            outputFile = new File(outputDir);
        }

        File fileBase = null;
        if (inputFile.isDirectory()) {
            fileBase = inputFile;
        } else {
            fileBase = inputFile.getParentFile();
        }

        Map<String, File> allFiles = getAllFiles(inputFile);

        for (Map.Entry<String, File> entry : allFiles.entrySet()) {
            String fileKey = entry.getKey();
            File value = entry.getValue();

            byte[] fileKeyBytes = fileKey.getBytes(StandardCharsets.UTF_8);

            System.out.println("file " + value.getAbsolutePath());
            try (FileInputStream fis = new FileInputStream(value)) {

                long length = value.length();
                int mb = (int) (length / PER_MB + (length % PER_MB == 0 ? 0 : 1));
                long fileCount = mb / MB_SIZE + (mb % MB_SIZE == 0 ? 0 : 1);
                long perFileSize = length / fileCount + (length % fileCount == 0 ? 0 : 1);

                System.out.println("perFileSize " + perFileSize + " fileCount " + fileCount + " available " + length);
                for (int fileIndex = 0; fileIndex * perFileSize < length; fileIndex++) {

                    File file = new File(outputFile, UUID.randomUUID() + PART_FILE_SUFFIX);

                    System.out.println("create file " + file.getName());
                    file.createNewFile();

                    byte[] seqBytes = intToBytes(fileIndex + 1);
                    int headSize = seqBytes.length + fileKeyBytes.length + 5;
                    byte[] headBytes = intToBytes(headSize);

                    try (FileOutputStream fos = new FileOutputStream(file, true)) {

                        // 文件头
                        fos.write(headBytes);
                        // 子文件序号
                        fos.write(seqBytes);
                        // 文件名
                        fos.write(fileKeyBytes);

                        int partSize = (int) Math.min(length - fileIndex * perFileSize, perFileSize);
                        byte[] bytes = new byte[PER_READ_SIZE];
                        int readSize = 0;

                        while (readSize < partSize) {
                            int read = fis.read(bytes, 0, Math.min(PER_READ_SIZE, partSize - readSize));

                            if (read == -1) {
                                break;
                            }
                            readSize += read;
                            // 文件内容
                            fos.write(bytes, 0, read);
                        }
                    }
                }
            }
        }

    }

    private static Map<String, List<PartFileInfo>> readPartFiles(File inputFile) throws IOException {
        File[] files = inputFile.listFiles();
        Map<String, List<PartFileInfo>> fileMap = new HashMap<>();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(PART_FILE_SUFFIX)) {
                    continue;
                }
                try (FileInputStream fis = new FileInputStream(file)) {
                    if (fis.available() < 5) {
                        continue;
                    }
                    byte[] headBytes = new byte[5];
                    int read = fis.read(headBytes);
                    if (read < headBytes.length) {
                        System.out.println("invalid file length " + read + " of file " + file.getAbsolutePath());
                        continue;
                    }

                    int headCount = bytesToInt(headBytes);

                    byte[] seqBytes = new byte[5];
                    read = fis.read(seqBytes);
                    if (read < seqBytes.length) {
                        System.out.println("invalid file length " + (read + headBytes.length) + " of file " + file.getAbsolutePath());
                        continue;
                    }
                    int part = bytesToInt(seqBytes);
                    if (headCount - 10 > 3000) {
                        System.out.println("invalid file name length " + (headCount - 10) + " of file " + file.getAbsolutePath());
                        continue;
                    }
                    byte[] nameBytes = new byte[headCount - 10];
                    read = fis.read(nameBytes);
                    if (read < nameBytes.length) {
                        System.out.println("invalid file name length " + nameBytes.length + " of file " + file.getAbsolutePath());
                        continue;
                    }

                    String fileKey = new String(nameBytes, StandardCharsets.UTF_8);
                    fileMap.compute(fileKey, (key, fileList) -> {
                        if (fileList == null) {
                            fileList = new ArrayList<>();
                        }
                        PartFileInfo partFileInfo = new PartFileInfo();
                        partFileInfo.setPart(part);
                        partFileInfo.setFileName(fileKey);
                        partFileInfo.setFile(file);
                        fileList.add(partFileInfo);

                        return fileList;
                    });
                }
            }
        }
        return fileMap;
    }

    private static void unpackageFile(String inputDir, String outputDir) throws IOException {
        String userBaseDir = System.getProperty("user.dir");

        File inputFile;
        if (!inputDir.startsWith("/")) {
            inputFile = new File(userBaseDir, inputDir);
        } else {
            inputFile = new File(inputDir);
        }

        File outputFile;
        if (!outputDir.startsWith("/")) {
            outputFile = new File(userBaseDir, outputDir);
        } else {
            outputFile = new File(outputDir);
        }

        if (!inputFile.isDirectory()) {
            throw new RuntimeException("file is not a directory");
        }

        Map<String, List<PartFileInfo>> partFileMap = readPartFiles(inputFile);

        for (String fileKey : partFileMap.keySet()) {

            List<PartFileInfo> partFileInfos = partFileMap.get(fileKey);

            partFileInfos.sort(Comparator.comparing(PartFileInfo::getPart));

            System.out.println("restore " + fileKey);

            List<String> hierarchyNames = toHierarchyNames(fileKey);
            File file = outputFile;
            for (int i = 0; i < hierarchyNames.size(); i++) {
                String hierarchyName = hierarchyNames.get(i);
                file = new File(file, hierarchyName);
                if (i < hierarchyNames.size() - 1) {
                    if (!file.exists()) {
                        file.mkdir();
                        System.out.println("mkdir " + file.getAbsolutePath());
                    }
                } else {
                    if (file.exists()) {
                        file.delete();
                    }
                    file.createNewFile();
                    System.out.println("create file " + file.getAbsolutePath());
                }
            }

            for (PartFileInfo partFileInfo : partFileInfos) {

                File partFile = partFileInfo.getFile();

                try (FileInputStream partFis = new FileInputStream(partFile)) {

                    byte[] headBytes = new byte[5];
                    partFis.read(headBytes);
                    int headSize = bytesToInt(headBytes);

                    partFis.read(new byte[headSize - 5]);

                    int read;
                    byte[] bytes = new byte[1024];
                    try (FileOutputStream fos = new FileOutputStream(file, true)) {

                        while ((read = partFis.read(bytes)) != -1) {
                            fos.write(bytes, 0, read);
                        }
                    }
                }
            }
        }
    }

    private static class PartFileInfo {

        private int part;

        private String fileName;

        private File file;

        public int getPart() {
            return part;
        }

        public void setPart(int part) {
            this.part = part;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public File getFile() {
            return file;
        }

        public void setFile(File file) {
            this.file = file;
        }
    }
}
