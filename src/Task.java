import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Task {
    private String id;
    private String command;
    private String filePath;
    private STATUS status;
    private String params;
private RESULTS result;
private String additionalinfo;
    enum STATUS{
        PENDING,
        IN_PROGRESS,
        COMPLETE
    }

    enum RESULTS{
        PENDING,
        SUCCESS,
        ERROR,
        WARNING,
        ERROR_WITH_WARNING
    }

    public Task(String id, String command, String filePath, STATUS status,String params, RESULTS result,String additionalinfo) {
        this.id=id;
        this.command = command;
        this.filePath = filePath;
        this.status = status;
        this.params = params;
        this.result = result;
        this.additionalinfo = additionalinfo;
    }

    // Getters and setters
    // ...

    String getId()
    {
        return this.id;
    }
    void setResult(RESULTS newresult)
    {
        this.result=newresult;
    }
    RESULTS getResult()
    {
        return this.result;
    }
    String getParamsString()
    {
        return this.params;
    }
    Map<String,String> getParams()
    {
        Map<String,String> parsedparams =new HashMap<String, String>();

        for(String parameter : this.params.split(";",-1))
        {
            String key = parameter.split("=",-1)[0];
            String value = parameter.split("=",-1)[1];
            parsedparams.put(key,value);
        }
        return parsedparams;
    }
    void setAdditionalInfo (String newadditionalinfo)
    {
        this.additionalinfo=newadditionalinfo;
    }
    String getAdditionalInfo ()
    {
        return additionalinfo;
    }
    void setStatus(STATUS newstatus)
    {
        this.status = newstatus;
    }

    String getCommand()
    {
        return this.command;
    }

    String getFilePath()
    {
        return this.filePath;
    }

    String getFileName()
    {
        return this.filePath.split("/")[this.filePath.split("/").length-1];
    }
    STATUS getStatus()
    {
        return this.status;
    }
    @Override
    public String toString() {
        return "Task{" +
                "command='" + command + '\'' +
                ", filePath='" + filePath + '\'' +
                ", status='" + status + '\'' +
                ", params='" + params + '\'' +
                '}';
    }
}