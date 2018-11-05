import java.io.File;

public class Cache {
    public File file;

    public String last_date;

    public Cache(File file, String last_date){
        this.file = file;
        this.last_date = last_date;
    }
}
