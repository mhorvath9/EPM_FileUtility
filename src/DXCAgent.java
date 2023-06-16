import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Stream;

public class DXCAgent implements Runnable {

    enum EXECUTION_STATES {
        RUNNING,
        STOPPED
    }

    EXECUTION_STATES state;
    private Thread t;
    private String threadName;

    String basefolder;
    String localtempfolder;
    String queue_id = "agent.queue";
    String queue_path;
    String agentlock_id = "agent.lock";
    String agentlock_path;
    String cloudlock_id = "cloud.lock";

    String inbox_path;
    String outbox_path;

    EnvironmentInformation environment;

    List<Task> tasks = new ArrayList<>();

    DXCAgent(String name, EnvironmentInformation env) {
        state = EXECUTION_STATES.STOPPED;
        this.environment = env;
        threadName = name;
        basefolder=Main.getJarLocation(Main.class);
        localtempfolder = basefolder+File.separator+environment.EnvironmentIdentifier+File.separator+"temp"+File.separator;
        queue_path = localtempfolder+queue_id;
        agentlock_path = localtempfolder+agentlock_id;
        inbox_path=basefolder+File.separator+ environment.EnvironmentIdentifier+File.separator+"inbox";
        outbox_path=basefolder+File.separator+ environment.EnvironmentIdentifier+File.separator+"outbox";
    }

    public void start() {
        System.out.println("DXCAgent(" + Thread.currentThread().getId() + "): Starting " + threadName);



        if (t == null) {
            t = new Thread(this, threadName);
            t.start();
        }
    }

    boolean CheckforCloudLock() {
        boolean cloudlockfound = false;
        try {

            EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"),environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");

            JSONObject response = RestHelper.listfiles();
            //System.out.println(response);
            JSONArray items = (JSONArray) response.get("items");

            for (Object item : items) {
                String filename = ((JSONObject) item).get("name").toString();
                //System.out.println("DXCAgent("+Thread.currentThread().getId()+"): Found: "+filename);
                if (filename.equals(cloudlock_id)) cloudlockfound = true;
            }
            System.out.println("DXCAgent(" + Thread.currentThread().getId() + "): Cloud Lock " + (cloudlockfound ? "found" : "not found"));
        } catch (Throwable x) {
            System.err.println("Error: " + x.getMessage());
        }

        return cloudlockfound;
    }

    public void refreshQueue() {
        System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").refreshQueue(): Refreshing Queue");
        tasks.clear();
        try (BufferedReader br = new BufferedReader(new FileReader(queue_path))) {

            String line;
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",",-1);

                String id=values[0];
                String command = values[1];
                String filePath = values[2];
                String status = values[3];
                String params = values[4];
                String result = values[5];
                String additionalinfo = values[6];

                Task.STATUS newStatus = Task.STATUS.PENDING;
                switch (status) {
                    case "PENDING":
                        newStatus = Task.STATUS.PENDING;
                        break;
                    case "IN_PROGRESS":
                        newStatus = Task.STATUS.IN_PROGRESS;
                        break;
                    case "COMPLETE":
                        newStatus = Task.STATUS.COMPLETE;
                        break;
                }

                Task.RESULTS newResult = Task.RESULTS.PENDING;
                switch (result) {
                    case "PENDING":
                        newResult = Task.RESULTS.PENDING;
                        break;
                    case "SUCCESS":
                        newResult = Task.RESULTS.SUCCESS;
                        break;
                    case "ERROR":
                        newResult = Task.RESULTS.ERROR;
                        break;

                    case "WARNING":
                        newResult = Task.RESULTS.WARNING;
                        break;
                    case "ERROR_WITH_WARNING":
                        newResult = Task.RESULTS.ERROR_WITH_WARNING;
                        break;
                }
                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").refreshQueue(): Adding new task = " + command + "," + filePath + "," + newStatus + "," + params+ "," + result+ "," + additionalinfo);
                Task task = new Task(id, command, filePath, newStatus,params,newResult,additionalinfo);
                tasks.add(task);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    void saveQueue()
    {
        System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").saveQueue(): Saving Queue to local");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(queue_path))) {

            for (Task task : tasks) {
                bw.write(task.getId() + ","+task.getCommand() + "," + task.getFilePath() + "," + task.getStatus()+ "," + task.getParamsString()+ "," + task.getResult()+ "," + task.getAdditionalInfo());
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    void sendQueue() {
        System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").sendQueue(): Sending Queue to Cloud ");
        boolean wait=true;
        int counter=0;
        int timeout=20;
        while(wait)
            if (!CheckforCloudLock())
            try {
                EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");
                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").sendQueue(): Delete old queue on cloud ");
                RestHelper.deleteFile(queue_id);
                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").sendQueue(): Sending new queue to Cloud ");
                RestHelper.uploadfile(queue_path, null);
                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").sendQueue(): Deleting agent lock file");
                RestHelper.deleteFile(agentlock_id);
                wait=false;

            } catch (Throwable x) {
                System.err.println("Error: " + x.getMessage());
                wait=false;
            }
        else {
                counter++;
                try{
                    Thread.sleep(1000);
                } catch (Exception e)
                {
                    System.err.println("Error: Thread was interrupted.");
                    wait=false;
                }
                if (counter >= timeout)
                {
                    System.err.println("Error: Timed out waiting for cloud lock.");
                    wait=false;
                }
            }

    }

    void readQueue() {
        System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").readQueue(): Reading Queue from Cloud ");
        try {
            EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"),environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");
            System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").readQueue(): Uploading agentlock file");
            RestHelper.uploadfile(agentlock_path, null);

            while (true) {
                if (!CheckforCloudLock()) {
                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").readQueue(): Downloading Queue");
                    RestHelper.downloadfile(environment.getValue("cloudserver.applicationName"), queue_id, localtempfolder);
                    break;
                } else {
                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").readQueue(): Cloud Lock found, waiting for 1s...");
                    Thread.sleep(1000);
                }
            }
        } catch (Exception e) {
            System.err.println("DXCAgent(" + Thread.currentThread().getId() + ").readQueue(): Error " + e.getMessage());
        }

    }

    void releaseQueue(){
        System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").releaseQueue(): Releasing cloud queue ");
        try {
            EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"),environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");
            System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").releaseQueue(): Deleting agent lock file");
            RestHelper.deleteFile(agentlock_id);
        } catch (Throwable x) {
            System.err.println("Error: " + x.getMessage());
        }
    }

    public void run()
    {
        System.out.println("DXCAgent("+Thread.currentThread().getId()+").run(): Running " + threadName);
        state=EXECUTION_STATES.RUNNING;

        while (state==EXECUTION_STATES.RUNNING) {

            readQueue();
            refreshQueue();

            System.out.println("DXCAgent("+Thread.currentThread().getId()+").run(): Task queue length = "+tasks.size());

            if (tasks.size() == 0) releaseQueue();
            for (int i=0;i< tasks.size();i++) {
                if(tasks.get(i).getStatus() == Task.STATUS.PENDING) {
                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Processing " + tasks.get(i));
                        switch (tasks.get(i).getCommand()) {
                        case "DOWNLOAD":
                            try {
                                tasks.get(i).setStatus(Task.STATUS.IN_PROGRESS);
                                tasks.get(i).setResult(Task.RESULTS.PENDING);
                                saveQueue();
                                sendQueue();
                                readQueue();
                                refreshQueue();

                                EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");

                                String locallocation= inbox_path+File.separator+tasks.get(i).getParams().get("outputfolder").toString();
                                String cloudlocation=tasks.get(i).getFilePath();
                                int result = RestHelper.downloadfile(environment.getValue("cloudserver.applicationname"), cloudlocation,locallocation);

                                tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                if (result == 0) {
                                    tasks.get(i).setResult(Task.RESULTS.SUCCESS);
                                    tasks.get(i).setAdditionalInfo("The FTU has downloaded "+tasks.get(i).getFileName()+" successfully.");
                                } else {
                                    tasks.get(i).setResult(Task.RESULTS.ERROR);
                                    tasks.get(i).setAdditionalInfo("The FTU has reported the download of "+tasks.get(i).getFileName()+" failed to complete.");
                                }
                                saveQueue();
                                sendQueue();


                            } catch (Throwable x) {
                                releaseQueue();
                                System.err.println("Error: " + x.getMessage());
                                tasks.get(i).setStatus(Task.STATUS.PENDING);
                                tasks.get(i).setResult(Task.RESULTS.ERROR);
                                tasks.get(i).setAdditionalInfo("The FTU has reported the download of "+tasks.get(i).getFileName()+" failed to complete.");
                            }

                            break;

                        case "UPLOAD":
                            try {
                                tasks.get(i).setStatus(Task.STATUS.IN_PROGRESS);
                                tasks.get(i).setResult(Task.RESULTS.PENDING);
                                saveQueue();
                                sendQueue();
                                readQueue();
                                refreshQueue();

                                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Performing upload operation");

                                String cloudfolder = tasks.get(i).getParams().get("cloudfolder");
                                String cloudpath = tasks.get(i).getParams().get("cloudfolder") + "/" + tasks.get(i).getFileName();
                                if (cloudfolder.isEmpty()) cloudfolder = null;

                                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Upload cloudfolder=" + cloudfolder);

                                EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");
                                boolean deleteresult = RestHelper.deleteFile(cloudpath);
                                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Deleted " + cloudfolder + "/" + tasks.get(i).getFileName() + " successfully? " + deleteresult);

                                String locallocation= outbox_path+File.separator+tasks.get(i).getFilePath();

                                boolean result = RestHelper.uploadfile(locallocation, cloudfolder);
                                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Uploaded " + tasks.get(i).getFilePath() + " successfully? " + result);

                                tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                if (result) {
                                    tasks.get(i).setResult(Task.RESULTS.SUCCESS);
                                    tasks.get(i).setAdditionalInfo("The FTU has downloaded "+tasks.get(i).getFileName()+" successfully.");
                                } else {
                                    tasks.get(i).setResult(Task.RESULTS.ERROR);
                                    tasks.get(i).setAdditionalInfo("An error has occurred while uploading " + tasks.get(i).getFilePath());

                                }
                                saveQueue();
                                sendQueue();

                            } catch (Exception x) {
                                releaseQueue();
                                System.err.println("Exception: " + x.getMessage());
                                tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                tasks.get(i).setResult(Task.RESULTS.ERROR);
                            }
                            break;
                        case "UPLOADFOLDER":
                            try {
                                tasks.get(i).setStatus(Task.STATUS.IN_PROGRESS);
                                tasks.get(i).setResult(Task.RESULTS.PENDING);
                                saveQueue();
                                sendQueue();
                                readQueue();
                                refreshQueue();

                                File uploadfolder = new File(outbox_path+File.separator+tasks.get(i).getFilePath());
                                File[] uploadfiles = uploadfolder.listFiles();
                                boolean result = true;

                                EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");

                                if (uploadfiles != null) {
                                    for (File uploadfile : uploadfiles) {
                                        if (uploadfile.isFile()) {
                                            RestHelper.deleteFile(tasks.get(i).getParams().get("cloudfolder") + "/" + uploadfile.getName());
                                            result = RestHelper.uploadfile(uploadfile.getPath(), tasks.get(i).getParams().get("cloudfolder"));
                                        }
                                    }
                                }

                                tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                tasks.get(i).setResult(Task.RESULTS.SUCCESS);
                                tasks.get(i).setAdditionalInfo("The FTU has uploaded all files in  "+outbox_path+File.separator+tasks.get(i).getFilePath()+" successfully.");

                                saveQueue();
                                sendQueue();

                            } catch (Exception x) {
                                releaseQueue();
                                System.err.println("Exception: " + x.getMessage());
                                tasks.get(i).setStatus(Task.STATUS.PENDING);
                                tasks.get(i).setResult(Task.RESULTS.ERROR);
                                tasks.get(i).setAdditionalInfo("An error has occurred while uploading one or more files in " + tasks.get(i).getFilePath());
                            }
                            break;
                        case "REFRESH":
                            try {
                                tasks.get(i).setStatus(Task.STATUS.IN_PROGRESS);
                                tasks.get(i).setResult(Task.RESULTS.PENDING);
                                saveQueue();
                                sendQueue();
                                readQueue();
                                refreshQueue();


                                EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");
                                boolean result = RestHelper.refreshcube(environment.getValue("cloudserver.applicationname"), "Refresh_with_Kill", EPMREST.REST_USER_SETTING.ALL_USERS, false, false, EPMREST.REST_USER_SETTING.ALL_USERS);

                                result = RestHelper.executeRule(environment.getValue("cloudserver.applicationname"), tasks.get(i).getFileName(), tasks.get(i).getParams(), false);

                                tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                if (result) {
                                    tasks.get(i).setResult(Task.RESULTS.SUCCESS);
                                    tasks.get(i).setAdditionalInfo("The FTU has executed a refresh and started  "+tasks.get(i).getFileName()+" successfully.");
                                } else {
                                    tasks.get(i).setResult(Task.RESULTS.ERROR);
                                    tasks.get(i).setAdditionalInfo("An error has occurred while performing refresh using " + tasks.get(i).getFilePath());

                                }
                                saveQueue();
                                sendQueue();

                            } catch (Exception x) {
                                releaseQueue();
                                System.err.println("Exception: " + x.getMessage());
                            }
                            break;
                            case "RESTART":
                                try {
                                    tasks.get(i).setStatus(Task.STATUS.IN_PROGRESS);
                                    tasks.get(i).setResult(Task.RESULTS.PENDING);
                                    saveQueue();
                                    sendQueue();
                                    readQueue();
                                    refreshQueue();


                                    EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");
                                    boolean result = RestHelper.hardReset("DXC Agent Restart Requested");

                                    result = RestHelper.executeRule(environment.getValue("cloudserver.applicationname"), tasks.get(i).getFileName(), tasks.get(i).getParams(), false);

                                    tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                    if (result) {
                                        tasks.get(i).setResult(Task.RESULTS.SUCCESS);
                                        tasks.get(i).setAdditionalInfo("The restart has completed successfully, and "+tasks.get(i).getFileName()+" has been started.");
                                    } else {
                                        tasks.get(i).setResult(Task.RESULTS.ERROR);
                                        tasks.get(i).setAdditionalInfo("An error has occurred while performing restart using " + tasks.get(i).getFilePath());

                                    }
                                    saveQueue();
                                    sendQueue();

                                } catch (Exception x) {
                                    releaseQueue();
                                    System.err.println("Exception: " + x.getMessage());
                                }
                                break;

                            case "RENAME":
                                try {
                                    tasks.get(i).setStatus(Task.STATUS.IN_PROGRESS);
                                    tasks.get(i).setResult(Task.RESULTS.PENDING);
                                    saveQueue();
                                    sendQueue();
                                    readQueue();
                                    refreshQueue();

                                    String cloudsource= tasks.get(i).getFilePath();
                                    String cloudtargetpath= tasks.get(i).getParams().get("targetpath");
                                    String newfilename = tasks.get(i).getParams().get("newfilename");
                                    if (cloudtargetpath.isEmpty()) cloudtargetpath = null;

                                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Performing rename operation");
                                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Rename "+cloudsource+" to "+newfilename + " and upload back to "+ cloudtargetpath);

                                    EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");
                                    int Download_result = RestHelper.downloadfile(environment.getValue("cloudserver.applicationname"), cloudsource, localtempfolder);

                                    String oldfilename=cloudsource.split("/")[cloudsource.split("/").length-1];

                                    String targetfilename=localtempfolder+File.separator+newfilename;

                                    File FileSource = new File(localtempfolder+File.separator+oldfilename);
                                    File FileTarget = new File(targetfilename);

                                    boolean Rename_result = FileSource.renameTo(FileTarget);

                                    String cloudtarget = (cloudtargetpath!=null)?cloudtargetpath + "/" + newfilename:newfilename;

                                    boolean Deleteold_result = RestHelper.deleteFile(cloudtarget);
                                    boolean Upload_result = RestHelper.uploadfile(targetfilename, cloudtargetpath);

                                    boolean Delete_result=false;
                                    if (Upload_result) Delete_result = RestHelper.deleteFile(cloudsource);

                                    tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                    if (Upload_result) {
                                        tasks.get(i).setResult(Task.RESULTS.SUCCESS);
                                        tasks.get(i).setAdditionalInfo("The FTU has renamed file successfully.");
                                    } else {
                                        tasks.get(i).setResult(Task.RESULTS.ERROR);
                                        tasks.get(i).setAdditionalInfo("An error has occurred while performing rename using " + tasks.get(i).getFilePath());

                                    }
                                    saveQueue();
                                    sendQueue();

                                } catch (Exception x) {
                                    releaseQueue();
                                    System.err.println("Exception: " + x.getMessage());
                                }
                                break;

                            case "EXTRACT":
                                try {
                                    tasks.get(i).setStatus(Task.STATUS.IN_PROGRESS);
                                    tasks.get(i).setResult(Task.RESULTS.PENDING);
                                    saveQueue();
                                    sendQueue();
                                    readQueue();
                                    refreshQueue();

                                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Performing file extract operation");

                                    String cloudfolder = tasks.get(i).getParams().get("cloudfolder");
                                    if (cloudfolder.isEmpty()) cloudfolder = null;
                                    String localextractpath=localtempfolder+"extractedfiles";

                                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Download from " + tasks.get(i).getFilePath());
                                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Upload to cloudfolder=" + cloudfolder);

                                    EPMREST RestHelper = new EPMREST(environment.getValue("cloudserver.user"), environment.decodeValue("cloudserver.password"), environment.getValue("cloudserver.url"), "v2");

                                    int result = RestHelper.downloadfile(environment.getValue("cloudserver.applicationname"), tasks.get(i).getFilePath(), localtempfolder);

                                    String zipFile=tasks.get(i).getFileName();

                                    // Create output directory if it doesn't exist
                                    Path outputPath = Paths.get(localextractpath);
                                    if (!Files.exists(outputPath)) {
                                        Files.createDirectories(outputPath);
                                    } else {
                                        try (Stream<Path> paths = Files.walk(outputPath)) {
                                            paths.filter(Files::isRegularFile)
                                                    .forEach(file -> {
                                                        try {
                                                            Files.delete(file);
                                                        } catch (IOException e) {
                                                            throw new RuntimeException("Failed to delete file: " + file, e);
                                                        }
                                                    });
                                        }
                                    }

                                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Preparing to extract "+localtempfolder+zipFile+" to "+localextractpath);

                                    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(localtempfolder + zipFile))) {
                                        ZipEntry zipEntry;
                                        while ((zipEntry = zis.getNextEntry()) != null) {
                                            File outputFile = outputPath.resolve(zipEntry.getName()).toFile();
                                            System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Extracting "+zipEntry.getName()+" to "+outputFile);

                                            // Create directories for any nested folders in the zip
                                            if (zipEntry.isDirectory()) {
                                                outputFile.mkdirs();
                                            } else {
                                                // Create parent directories if they don't exist
                                                outputFile.getParentFile().mkdirs();

                                                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                                                    byte[] buffer = new byte[1024];
                                                    int bytesRead;
                                                    while ((bytesRead = zis.read(buffer)) != -1) {
                                                        fos.write(buffer, 0, bytesRead);
                                                    }
                                                }
                                            }
                                            zis.closeEntry();
                                        }
                                    }


                                    File uploadfolder = new File(localextractpath);
                                    File[] uploadfiles = uploadfolder.listFiles();
                                    boolean bResult = true;
                                    System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Checking upload folder "+uploadfolder);

                                    if (uploadfiles != null) {
                                        System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Found files to upload.");
                                        for (File uploadfile : uploadfiles) {
                                            if (uploadfile.isFile()) {
                                                System.out.println("DXCAgent(" + Thread.currentThread().getId() + ").run(): Found  "+uploadfile);
                                                RestHelper.deleteFile(tasks.get(i).getParams().get("cloudfolder") + "/" + uploadfile.getName());
                                                bResult = RestHelper.uploadfile(uploadfile.getPath(), cloudfolder);
                                            }
                                        }
                                    }

                                    tasks.get(i).setStatus(Task.STATUS.COMPLETE);
                                    if (bResult) {
                                        tasks.get(i).setResult(Task.RESULTS.SUCCESS);
                                        tasks.get(i).setAdditionalInfo("The FTU has extracted file successfully.");
                                    } else {
                                        tasks.get(i).setResult(Task.RESULTS.ERROR);
                                        tasks.get(i).setAdditionalInfo("An error has occurred while extracting " + tasks.get(i).getFilePath());

                                    }
                                    saveQueue();
                                    sendQueue();

                                } catch (Exception x) {
                                    releaseQueue();
                                    System.err.println("Exception: " + x.getMessage());
                                    tasks.get(i).setStatus(Task.STATUS.PENDING);
                                    tasks.get(i).setResult(Task.RESULTS.ERROR);
                                }
                                break;
                        default:
                            break;
                    }
                }
            }

            //wait
            try {
                System.out.println("DXCAgent("+Thread.currentThread().getId()+"): Waiting for 10000ms...");
                Thread.sleep(10000);
            } catch (InterruptedException e)
            {
                System.out.println(e.getMessage());
            }
            System.out.println("DXCAgent("+Thread.currentThread().getId()+"): Current state is "+state);
        }

    }

    void stopGracefully()
    {
        System.out.println("DXCAgent("+Thread.currentThread().getId()+").stopGracefully(): Stopping process...");
        state=EXECUTION_STATES.STOPPED;
        releaseQueue();
        System.out.println("DXCAgent("+Thread.currentThread().getId()+").stopGracefully(): Current state is "+state);
    }




}



