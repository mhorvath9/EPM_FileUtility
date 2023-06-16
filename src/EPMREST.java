import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.DatatypeConverter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;

public class EPMREST {
    private static String userName;
    private static String password;
    private String serverUrl;
    private String apiVersion;



    public EPMREST(String userName, String password, String serverUrl, String apiVersion) throws Exception {
        this.userName = userName;
        this.password = password;
        this.serverUrl = serverUrl;
        this.apiVersion = apiVersion;
    }

    public void setApiVersion(String newApiVersion)
    {
        this.apiVersion=newApiVersion;
    }

        public boolean hardReset(String comment) throws Exception {

            long maxLoopTime=(60 * 60 * 1000);

            JSONObject params = new JSONObject();
            params.put("comment",comment);
            JSONObject innerParams = new JSONObject();
            innerParams.put("autotune","true");
            params.put("parameters",innerParams);

            String urlString = String.format("%s/interop/rest/%s/config/services/reset", serverUrl, apiVersion);
            long startTime=System.currentTimeMillis();
            long endTime = startTime+maxLoopTime;
            String response = executeRequest(urlString, "POST", params.toString(), "application/json");
            getJobStatus(fetchPingUrlFromResponse(response, "Job Status"),"GET");

            return true;
        }

    public JSONObject listfiles() {
        System.out.println("CSSREST("+Thread.currentThread().getId()+"): Running listfiles()");
        Map<String, String> restResult = new HashMap<String, String>();
        try {
            String url = this.serverUrl + "/interop/rest/" + apiVersion + "/files/list";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, null ,
                    "GET");

        } catch (Exception e) {
            e.printStackTrace();
        }
        JSONObject jsonResponse=new JSONObject();
        try {
            jsonResponse = new JSONObject(restResult.get(RESTHelper.REST_CALL_RESPONSE));
        } catch (Exception ex) {
            ex.printStackTrace();
        }


        System.out.println("CSSREST("+Thread.currentThread().getId()+"): returning from listfiles()");
        return jsonResponse;
    }

    public int downloadfile(String applicationName, String fileName,String downloadlocation)
            throws Exception {
        System.out.println("CSSREST("+Thread.currentThread().getId()+"): Running downloadfile( "+applicationName+" , "+fileName+" , "+downloadlocation+" )");

        HttpURLConnection connection = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        int status = -1;
        int downloadstatus = -1;
        try {
            fileName = fileName.replaceAll("/", "\\\\");
            URL url = new URL(String.format("%s/interop/rest/%s/applicationsnapshots/%s/contents", serverUrl,
                    "11.1.2.3.600", URLEncoder.encode(fileName, "UTF-8")));

            System.out.println("DOWNLOAD URL: " + url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Authorization",
                    "Basic " + new sun.misc.BASE64Encoder().encode((userName + ":" + password).getBytes()));
            status = connection.getResponseCode();
            if (status == 200) {
                if (connection.getContentType() != null && connection.getContentType().equals("application/json")) {
                    JSONObject json = new JSONObject(RESTHelper.getStringFromInputStream(connection.getInputStream()));
                    System.out.println("Error downloading file : " + json.getString("details"));
                    downloadstatus = 1;
                } else {
                    inputStream = connection.getInputStream();
                    outputStream = RESTHelper.downloadContent(connection, inputStream,fileName,downloadlocation);
                    downloadstatus =0;
                }
            } else {
                throw new Exception("Http status code: " + status);
            }
        } catch (Exception e)
        {
            status = -2;
            downloadstatus =2;
            System.out.println("Error downloading file : " + e.getMessage());
        }
        finally {
            if (connection != null)
                connection.disconnect();
            if (outputStream != null)
                outputStream.close();
            if (inputStream != null)
                inputStream.close();
        }

        System.out.println("CSSREST("+Thread.currentThread().getId()+"): returning from downloadfile()");
        return downloadstatus;
    }

    enum REST_USER_SETTING {
        ADMINISTRATORS,
        ALL_USERS
    }
    public boolean refreshcube(String applicationName, String jobName, REST_USER_SETTING allowedUsersDuringCubeRefresh, boolean terminateActiveRequestsBeforeCubeRefresh, boolean logOffAllUsersBeforeCubeRefresh, REST_USER_SETTING allowedUsersAfterCubeRefresh)
    {
        try {
            return executeJob(applicationName, "CUBE_REFRESH", jobName, null,true);
        } catch (Exception e)
        {
            System.out.println("An exception has occurred while executing refreshcube(). Exception:" + e.getMessage());
            return false;
        }
    }

    public boolean executeJob(String applicationName, String jobType, String jobName, Map<String,String> parameters, boolean waitforcomplete) throws Exception {
        String urlString = String.format("%s/HyperionPlanning/rest/%s/applications/%s/jobs", serverUrl, "v3", applicationName);
        System.out.println("CSSREST("+Thread.currentThread().getId()+").executeJob(): Running executeJob( "+applicationName+" , "+jobType+" , "+jobName+" , "+parameters+")");
        System.out.println("CSSREST("+Thread.currentThread().getId()+").executeJob(): URL: "+urlString+")");
        JSONObject payload = new JSONObject();
        payload.put("jobName",jobName);
        payload.put("jobType",jobType);
        payload.put("parameters",new JSONObject(parameters));
        String response = executeRequest(urlString, "POST", payload.toString(), "application/json");
        System.out.println("Job started successfully");
        if (waitforcomplete) getJobStatus(fetchPingUrlFromResponse(response, "self"), "GET");
        return true;
    }

    public boolean executeRule(String applicationName, String jobName, Map<String,String> parameters,boolean waitforcomplete)
    {
        try {
            return executeJob(applicationName, "Rules", jobName, parameters,waitforcomplete);
        } catch (Exception e)
        {
            System.out.println("An exception has occurred while executing executeRule(). Exception:" + e.getMessage());
            return false;
        }
    }

    public String fetchPingUrlFromResponse(String response, String relValue) throws Exception {
        String pingUrlString = null;
        JSONObject jsonObj = new JSONObject(response);
        int resStatus = jsonObj.getInt("status");
        if (resStatus <= 0) {
            JSONArray lArray = jsonObj.getJSONArray("links");
            for (int i = 0; i < lArray.length(); i++) {
                JSONObject arr = lArray.getJSONObject(i);
                if (arr.get("rel").equals(relValue))
                    pingUrlString = (String) arr.get("href");
            }
        }
        return pingUrlString;
    }
    private String executeRequest(String urlString, String requestMethod, String payload, String contentType) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(requestMethod);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Authorization", "Basic " + new sun.misc.BASE64Encoder().encode((userName + ":" + password).getBytes()));
            connection.setRequestProperty("Content-Type", contentType);
            if (payload != null) {
                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
                writer.write(payload);
                writer.flush();
            }
            int status = connection.getResponseCode();
            if (status == 200 || status == 201) {
                return getStringFromInputStream(connection.getInputStream());
            }
            throw new Exception("Http status code: " + status);
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }
    private void getJobStatus(String pingUrlString, String methodType) throws Exception {
        boolean completed = false;
        int timeout = 900;
        int timeoutcounter = 0;
        while (!completed) {
            try {
                timeoutcounter++;
                String pingResponse = executeRequest(pingUrlString, methodType, null, "application/x-www-form-urlencoded");
                JSONObject json = new JSONObject(pingResponse);
                int status = json.getInt("status");
                if (status == -1) {
                    try {
                        System.out.println("Waiting for a response. Retrying " + timeoutcounter + " of " + timeout);
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                            completed = true;
                            throw e;
                    }
                } else {
                    if (status > 0) {
                        System.out.println("Error occurred: " + json.getString("details"));
                    } else {
                        completed = true;
                        System.out.println("Completed");
                    }

                }
                if (timeoutcounter > timeout) {
                    completed = true;
                    System.out.println("EPMREST.getJobStatus(): Timed out waiting for a valid response from EPM Cloud server.");
                }
            } catch (Exception e) {
                System.out.println("An error has occurred trying to get the job status. Retrying " + timeoutcounter + " of " + timeout);
            }
        }
    }
    private String getStringFromInputStream(InputStream is) {
        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();
        String line;

        try {
            br = new BufferedReader(new InputStreamReader(is));
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return sb.toString();
    }

    public boolean uploadfile(String filePath,String extDirPath)
            throws Exception
        {
            System.out.println("EPMREST("+Thread.currentThread().getId()+").uploadfile(): Uploading file "+filePath);

            String details = null;
            HttpURLConnection connection = null;
            FileInputStream content = null;
            File file = new File(filePath);

            try {
                String restURL = String.format(
                        "%s/interop/rest/11.1.2.3.600/applicationsnapshots/%s/contents",
                        serverUrl, URLEncoder.encode(file.getName(), "UTF-8"));
                if(null != extDirPath)
                    restURL = restURL + "?extDirPath="+extDirPath;
                URL url = new URL(restURL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setInstanceFollowRedirects(false);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setDoInput(true);

                String creds = null;


                creds = userName + ":" + password;

                connection.setRequestProperty("Authorization",
                        "Basic " + new sun.misc.BASE64Encoder().encode(creds.getBytes()));
                connection.setRequestProperty("Content-Type", "application/octet-stream");

                content = new FileInputStream(file);
                OutputStream paramOutputStream = connection.getOutputStream();
                if (content != null) {
                    byte[] arrayOfByte = new byte[4096];
                    boolean hasMore = true;
                    while (hasMore) {
                        int j = content.read(arrayOfByte);
                        if (j < 0) {
                            hasMore = false;
                            continue;
                        }
                        paramOutputStream.write(arrayOfByte, 0, j);
                    }
                }

                int statusCode = connection.getResponseCode();

                String responseBody = RESTHelper.getStringFromInputStream(connection.getInputStream());
                if (statusCode == 200 && responseBody != null) {
                    int commandStatus = RESTHelper.getCommandStatus(responseBody);
                    if (commandStatus == -1) {
                        RESTHelper.getJobStatus(RESTHelper.fetchPingUrlFromResponse(responseBody, "Job Status"), "GET");
                    }
                    if (commandStatus == 0) {
                        System.out.println("EPMREST("+Thread.currentThread().getId()+").uploadfile(): Upload successful ");
                        return true;
                    }
                    else{
                        System.out.println("EPMREST("+Thread.currentThread().getId()+").uploadfile(): Upload unsuccessful ");
                        details = RESTHelper.getDetails(responseBody);
                        System.out.println("EPMREST("+Thread.currentThread().getId()+").uploadfile(): Upload details = "+details);
                    }
                }
                return false;
            } finally {
                if(null != content)
                    content.close();
                if (connection != null)
                    connection.disconnect();
            }
        }

    public boolean deleteFile(String fileName) throws Exception {
        String urlString = String.format("%s/interop/rest/%s/applicationsnapshots/%s", serverUrl, "11.1.2.3.600", URLEncoder.encode(fileName, "UTF-8"));
        String response = RESTHelper.executeRequest(urlString, "DELETE", null, "application/x-www-form-urlencoded");
        JSONObject json = new JSONObject(response);
        int resStatus = json.getInt("status");
        if (resStatus == 0)
        {
            System.out.println("File deleted successfully");

        return true;
        }
        else
        System.out.println("Error deleting file : " + json.getString("details"));

        return false;
    }

    public void addUsers(String fileName, String userPassword, boolean resetPassword) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/users";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("userpassword", userPassword);
            reqParams.put("resetpassword", resetPassword + "");

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "POST");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void removeUsers(String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/users";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "DELETE");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void assignRole(String fileName, String roleName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/users";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("jobtype", "ASSIGN_ROLE");
            reqParams.put("rolename", roleName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "PUT");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void unassignRole(String fileName, String roleName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/users";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("jobtype", "UNASSIGN_ROLE");
            reqParams.put("rolename", roleName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "PUT");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addUsersToGroup(String fileName, String groupName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/groups";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("jobtype", "ADD_USERS_TO_GROUP");
            reqParams.put("groupname", groupName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "PUT");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeUsersFromGroup(String fileName, String groupName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/groups";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("jobtype", "REMOVE_USERS_FROM_GROUP");
            reqParams.put("groupname", groupName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "PUT");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addUserToGroups(String fileName, String userName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/groups";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("jobtype", "ADD_USER_TO_GROUPS");
            reqParams.put("username", userName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "PUT");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeUserFromGroups(String fileName, String userName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/groups";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("jobtype", "REMOVE_USER_FROM_GROUPS");
            reqParams.put("username", userName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "PUT");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void generateRoleAssignmentReport(String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/roleassignmentreport";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "POST");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void generateUserGroupReport(String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/usergroupreport";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "POST");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addGroups(String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/groups";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "POST");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeGroups(String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/groups";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "DELETE");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void generateInvalidLoginReport(String fromDate, String toDate,String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/invalidloginreport";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("from_date", fromDate);
            reqParams.put("to_date", toDate);
            reqParams.put("filename", fileName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "POST");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void generateRoleAssignmentAuditReport(String fromDate, String toDate,String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/roleassignmentauditreport";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("from_date", fromDate);
            reqParams.put("to_date", toDate);
            reqParams.put("filename", fileName);

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "POST");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateUsers(String fileName) {
        try {
            String url = this.serverUrl + "/interop/rest/security/" + apiVersion + "/users";
            Map<String, String> reqHeaders = new HashMap<String, String>();
            reqHeaders.put("Authorization", "Basic " + DatatypeConverter
                    .printBase64Binary((this.userName + ":" + this.password).getBytes(Charset.defaultCharset())));

            Map<String, String> reqParams = new HashMap<String, String>();
            reqParams.put("filename", fileName);
            reqParams.put("jobtype", "UPDATE_USERS");

            Map<String, String> restResult = RESTHelper.callRestApi(new HashMap(), url, reqHeaders, reqParams,
                    "PUT");
            String jobStatus = RESTHelper.getCSSRESTJobCompletionStatus(restResult, reqHeaders);
            System.out.println(jobStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class RESTHelper {


        public static String fetchPingUrlFromResponse(String response, String relValue) throws Exception {
            String pingUrlString = null;
            JSONObject jsonObj = new JSONObject(response);
            int resStatus = jsonObj.getInt("status");
            if (resStatus == -1) {
                JSONArray lArray = jsonObj.getJSONArray("links");
                for (int i = 0; i < lArray.length(); i++) {
                    JSONObject arr = lArray.getJSONObject(i);
                    if (arr.get("rel").equals(relValue))
                        pingUrlString = (String) arr.get("href");
                }
            }
            return pingUrlString;
        }
        private static String executeRequest(String urlString, String requestMethod, String payload, String contentType) throws Exception {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(requestMethod);
                connection.setInstanceFollowRedirects(false);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setDoInput(true);
                connection.setRequestProperty("Authorization", "Basic " + new sun.misc.BASE64Encoder().encode((userName + ":" + password).getBytes()));
                connection.setRequestProperty("Content-Type", contentType);
                if (payload != null) {
                    OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
                    writer.write(payload);
                    writer.flush();
                }
                int status = connection.getResponseCode();
                if (status == 200 || status == 201) {
                    return getStringFromInputStream(connection.getInputStream());
                }
                throw new Exception("Http status code: " + status);
            } finally {
                if (connection != null)
                    connection.disconnect();
            }
        }

        private static void getJobStatus(String pingUrlString, String methodType) throws Exception {
            boolean completed = false;
            while (!completed) {
                String pingResponse = executeRequest(pingUrlString, methodType, null, "application/x-www-form-urlencoded");
                JSONObject json = new JSONObject(pingResponse);
                int status = json.getInt("status");
                if (status == -1) {
                    try {
                        System.out.println("Please wait...");
                        Thread.sleep(20000);
                    } catch (InterruptedException e) {
                        completed = true;
                        throw e;
                    }
                }
                else {
                    if (status > 0) {
                        System.out.println("Error occurred: " + json.getString("details"));
                    }
                    else {
                        System.out.println("Completed");
                    }
                    completed = true;
                }
            }
        }


        private static int getCommandStatus(String response) throws Exception {
            JSONObject json = new JSONObject(response);
            if (!JSONObject.NULL.equals(json.get("status")))
                return json.getInt("status");
            else
                return Integer.MIN_VALUE;
        }
        private static String getDetails(String response) throws Exception {
            JSONObject json = new JSONObject(response);
            if (!JSONObject.NULL.equals(json.get("details")))
                return json.getString("details");
            else
                return "NA";
        }

        private static FileOutputStream downloadContent(HttpURLConnection connection, InputStream inputStream, String fileName, String downloadpath)
                throws FileNotFoundException, IOException {
            FileOutputStream outputStream;
            String downloadedFileName = fileName;
            if (fileName.lastIndexOf("/") != -1) {
                downloadedFileName = fileName.substring(fileName.lastIndexOf("/") + 1);
            }

            String ext = ".zip";
            if (connection.getHeaderField("fileExtension") != null) {
                ext = "." + connection.getHeaderField("fileExtension");
            }
            if (fileName.lastIndexOf(".") != -1 && fileName.lastIndexOf(".") != 0)
                ext = fileName.substring(fileName.lastIndexOf(".") + 1);

            outputStream = new FileOutputStream(new File(downloadpath + downloadedFileName));
            int bytesRead = -1;
            byte[] buffer = new byte[5 * 1024 * 1024];
            while ((bytesRead = inputStream.read(buffer)) != -1)
                outputStream.write(buffer, 0, bytesRead);
            System.out.println("File download completed.");
            return outputStream;
        }

        private static String getStringFromInputStream(InputStream is) {
            BufferedReader br = null;
            StringBuilder sb = new StringBuilder();
            String line;

            try {
                br = new BufferedReader(new InputStreamReader(is));
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            return sb.toString();
        }
        
        public static final String REST_CALL_STATUS = "REST_CALL_STATUS";
        public static final String REST_CALL_RESPONSE = "REST_CALL_RESPONSE";

        private static Map<String, String> callRestApi(Map context, String url, Map<String, String> requestHeaders,
                                                       Map<String, String> requestParams, String methodType) {
            System.out.println("CSSREST("+Thread.currentThread().getId()+").callRestApi(): Executing REST request");
            HttpURLConnection urlConnection = null;
            Map<String, String> restResult = new HashMap<String, String>();
            restResult.put(REST_CALL_STATUS, "-1");
            boolean isPostMethod = "POST".equalsIgnoreCase(methodType) || "PUT".equalsIgnoreCase(methodType);
            try {
                URI baseUri = new URI(url);
                URI uri = null;
                String reqParams = (requestParams != null ? buildRequestParams(context, requestParams, isPostMethod)
                        : null);
                if (isPostMethod) {
                    uri = new URI(baseUri.getScheme(), baseUri.getAuthority(), baseUri.getPath(), null, null);
                } else {
                    uri = new URI(baseUri.getScheme(), baseUri.getAuthority(), baseUri.getPath(), reqParams, null);
                }

                System.out.println("CSSREST("+Thread.currentThread().getId()+").callRestApi(): Opening connection");
                urlConnection = (HttpURLConnection) uri.toURL().openConnection();
                urlConnection.setRequestMethod(methodType);

                if (requestHeaders != null) {
                    Set<String> requestHeaderKeys = requestHeaders.keySet();
                    for (String requestHeaderKey : requestHeaderKeys) {
                        urlConnection.setRequestProperty(requestHeaderKey, requestHeaders.get(requestHeaderKey));
                    }
                }

                urlConnection.setUseCaches(false);
                urlConnection.setDoOutput(true);
                urlConnection.setDoInput(true);

                if (isPostMethod) {
                    OutputStreamWriter writer = new OutputStreamWriter(urlConnection.getOutputStream(),
                            Charset.defaultCharset());
                    writer.write(reqParams);
                    writer.flush();
                }

                if (!isPostMethod) {
                    System.out.println("CSSREST("+Thread.currentThread().getId()+").callRestApi(): Connecting...");
                    urlConnection.connect();
                }

                int status = urlConnection.getResponseCode();
                System.out.println("CSSREST("+Thread.currentThread().getId()+").callRestApi(): Connection response status is "+status);
                restResult.put(REST_CALL_STATUS, String.valueOf(status));
                String response = readResponse(context,
                        (status >= 400 ? urlConnection.getErrorStream() : urlConnection.getInputStream()));
                restResult.put(REST_CALL_RESPONSE, response);
            } catch (Exception e) {
                restResult.put(REST_CALL_RESPONSE, e.getMessage());
            } finally {
                if (urlConnection != null) {
                    System.out.println("CSSREST("+Thread.currentThread().getId()+").callRestApi(): Disconnecting...");
                    urlConnection.disconnect();
                }
            }
            return restResult;
        }

        private static String buildRequestParams(Map context, Map<String, String> requestParams, boolean isPostMethod) {
            String reqParams = null;
            try {
                StringBuilder result = new StringBuilder();
                Set<String> reqParamKeys = requestParams.keySet();
                boolean first = true;
                for (String reqParamKey : reqParamKeys) {
                    if (first)
                        first = false;
                    else
                        result.append("&");
                    String reqParamValue = requestParams.get(reqParamKey);
                    result.append((isPostMethod ? URLEncoder.encode(reqParamKey, "UTF-8") : reqParamKey));
                    result.append("=");
                    result.append((isPostMethod ? URLEncoder.encode(reqParamValue, "UTF-8") : reqParamValue));
                }
                reqParams = result.toString();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            return reqParams;
        }

        private static String readResponse(Map context, InputStream urlInStream) {
            BufferedReader br = null;
            String response = "";
            try {
                String line;
                br = new BufferedReader(new InputStreamReader(urlInStream, Charset.defaultCharset()));
                while ((line = br.readLine()) != null) {
                    response += line;
                }
            } catch (Exception e) {
                response += e.getMessage();
            } finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return response;
        }

        private static String getCSSRESTJobUrlFromResponse(String response) {
            String jobUrl = "";
            try {
                JSONObject jsonResponse = new JSONObject(response);
                JSONArray links = (JSONArray) jsonResponse.get("links");
                JSONObject jobStatusLink = (JSONObject) links.get(1);
                jobUrl = jobStatusLink.get("href").toString();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return jobUrl;
        }

        private static String getCSSRESTJobStatusFromResponse(String response) {
            String jobStatus = "";
            try {
                JSONObject jsonResponse = new JSONObject(response);
                jobStatus = jsonResponse.get("status").toString();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return jobStatus;
        }

        private static String getCSSRESTJobCompletionStatus(Map<String, String> restResult, Map<String, String> reqHeader) {
            String completionStatus = "";
            try {
                String restStatus = restResult.get(RESTHelper.REST_CALL_STATUS);
                if (restStatus.equalsIgnoreCase("200")) {
                    String jobUrl = getCSSRESTJobUrlFromResponse(restResult.get(RESTHelper.REST_CALL_RESPONSE));
                    String restJobStatus = "-1";
                    Map<String, String> jobStatusResult = null;
                    while (restJobStatus.equalsIgnoreCase("-1")) {
                        jobStatusResult = RESTHelper.callRestApi(new HashMap(), jobUrl, reqHeader, null, "GET");
                        String jobStatusStatus = jobStatusResult.get(RESTHelper.REST_CALL_STATUS);
                        if (jobStatusStatus.equalsIgnoreCase("200")) {
                            restJobStatus = getCSSRESTJobStatusFromResponse(
                                    jobStatusResult.get(RESTHelper.REST_CALL_RESPONSE));
                        }
                        System.out.println("CSSREST("+Thread.currentThread().getId()+").getCSSRESTJobCompletionStatus(): Sleep for 1000ms ");
                        Thread.sleep(1000);
                    }
                    completionStatus = jobStatusResult.get(RESTHelper.REST_CALL_RESPONSE);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return completionStatus;
        }


    };
}

