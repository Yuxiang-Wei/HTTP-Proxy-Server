import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Scanner;

public class Proxy implements Runnable{

    public static void main(String[] args) {
        Proxy myProxy = new Proxy(10086);
        myProxy.listen();
    }

    private ServerSocket serverSocket;

    private volatile boolean running = true;

    static HashMap<String, Cache> cache;

    static HashSet<String> blockedSites;

    static HashSet<String> blockedIps;

    static HashMap<String, String> fishing;

    static ArrayList<Thread> servicingThreads;


    /**
     * Create the Proxy Server
     * @param port Port number to run proxy server from.
     */
    public Proxy(int port) {


        cache = new HashMap<>();
        blockedSites = new HashSet<>();
        blockedIps = new HashSet<>();
        fishing = new HashMap<>();

//        blockedSites.add("http://www.dilidili.wang/");
//        fishing.put("http://today.hit.edu.cn/", "http://www.hit.edu.cn");
//        blockedIps.add("127.0.0.1");
        // Create array list to hold servicing threads
        servicingThreads = new ArrayList<>();

        new Thread(this).start();

        try{
            // 从文件中加载已缓存的文件列表
            File cachedSites = new File("cachedSites.txt");
            if(!cachedSites.exists()){
                System.out.println("没有找到cachedSite.txt - 创建新的cachedSite.txt");
                cachedSites.createNewFile();
            } else {
                FileInputStream fileInputStream = new FileInputStream(cachedSites);
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                cache = (HashMap<String,Cache>)objectInputStream.readObject();
                fileInputStream.close();
                objectInputStream.close();
            }

            // 从文件中加载被禁网站
            File blockedSitesTxtFile = new File("blockedSites.txt");
            if(!blockedSitesTxtFile.exists()){
                System.out.println("没有找到blockedSites.txt - 创建新的blockedSites.txt");
                blockedSitesTxtFile.createNewFile();
            } else {
                FileInputStream fileInputStream = new FileInputStream(blockedSitesTxtFile);
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                blockedSites = (HashSet<String>)objectInputStream.readObject();
                fileInputStream.close();
                objectInputStream.close();
            }

            // 从文件中加载被禁IP
            File blockedIpsTxtFile = new File("blockedIps.txt");
            if(!blockedIpsTxtFile.exists()){
                System.out.println("没有找到blockedIps.txt - 创建新的blockedIps.txt");
                blockedIpsTxtFile.createNewFile();
            } else {
                FileInputStream fileInputStream = new FileInputStream(blockedIpsTxtFile);
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                blockedIps = (HashSet<String>)objectInputStream.readObject();
                fileInputStream.close();
                objectInputStream.close();
            }

            // 从文件中加载被禁网站
            File fishingTxtFile = new File("fishing.txt");
            if(!fishingTxtFile.exists()){
                System.out.println("没有找到fishing.txt - 创建新的fishing.txt");
                fishingTxtFile.createNewFile();
            } else {
                FileInputStream fileInputStream = new FileInputStream(fishingTxtFile);
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                fishing = (HashMap<String, String>)objectInputStream.readObject();
                fileInputStream.close();
                objectInputStream.close();
            }

        } catch (IOException e) {
            System.out.println("加载txt文件失败");
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            System.out.println("Class not found loading in preivously cached sites file");
            e.printStackTrace();
        }

        try {
            serverSocket = new ServerSocket(port);
            System.out.println("代理服务器正在监听 " + serverSocket.getLocalPort() + "..");
            running = true;
        } catch (SocketException se) {
            System.out.println("Socket Exception when connecting to client");
            se.printStackTrace();
        } catch (SocketTimeoutException ste) {
            System.out.println("Timeout occured while connecting to client");
        } catch (IOException io) {
            System.out.println("IO exception when connecting to client");
        }
    }

    public void listen(){
        while(running){
            try {
                Socket socket = serverSocket.accept();
                Thread thread = new Thread(new Request(socket));
                servicingThreads.add(thread);
                thread.start();
            } catch (SocketException e) {
                System.out.println("Server 已关闭");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void closeServer(){
        System.out.println("\nClosing Server..");
        running = false;
        try{
            FileOutputStream fileOutputStream = new FileOutputStream("cachedSites.txt");
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
            objectOutputStream.writeObject(cache);
            objectOutputStream.close();
            fileOutputStream.close();
            System.out.println("Cache已写入文件");

            FileOutputStream fileOutputStream2 = new FileOutputStream("blockedSites.txt");
            ObjectOutputStream objectOutputStream2 = new ObjectOutputStream(fileOutputStream2);
            objectOutputStream2.writeObject(blockedSites);
            objectOutputStream2.close();
            fileOutputStream2.close();
            System.out.println("被禁网址已写入文件");

            FileOutputStream fileOutputStream3 = new FileOutputStream("blockedIps.txt");
            ObjectOutputStream objectOutputStream3 = new ObjectOutputStream(fileOutputStream3);
            objectOutputStream3.writeObject(blockedIps);
            objectOutputStream3.close();
            fileOutputStream3.close();
            System.out.println("被禁IP已写入文件");

            FileOutputStream fileOutputStream4 = new FileOutputStream("fishing.txt");
            ObjectOutputStream objectOutputStream4 = new ObjectOutputStream(fileOutputStream4);
            objectOutputStream4.writeObject(fishing);
            objectOutputStream4.close();
            fileOutputStream4.close();
            System.out.println("钓鱼地址已写入文件");
            try{
                for(Thread thread : servicingThreads){
                    if(thread.isAlive()){
                        System.out.print("Waiting on "+  thread.getId()+" to close..");
                        thread.join();
                        System.out.println(" closed");
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            System.out.println("Error saving cache/blocked sites");
            e.printStackTrace();
        }

        try{
            System.out.println("Terminating Connection");
            serverSocket.close();
        } catch (Exception e) {
            System.out.println("Exception closing proxy's server socket");
            e.printStackTrace();
        }

    }


    public static Cache getCachedPage(String url){
        return cache.get(url);
    }


    public static void addCachedPage(String urlString, Cache fileToCache){
        cache.put(urlString, fileToCache);
    }


    public static boolean isSiteBlocked (String url){
        return  blockedSites.contains(url);
    }

    public static boolean isIpBlocked (String ip){
        return  blockedIps.contains(ip);
    }

    public static boolean isFishing (String url){
        if(fishing.containsKey(url)){
            return true;
        }else {
            return false;
        }
    }

    public static String fishingTo (String url){
        return fishing.get(url);
    }


    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);

        String command;
        while(running){
            System.out.println("Enter new site to block, or type \"blocked\" to see blocked sites, \"cached\" to see cached sites, or \"close\" to close server.");
            command = scanner.nextLine();
            if(command.toLowerCase().equals("blocked")){
                System.out.println("\nCurrently Blocked Sites");
                for(String key : blockedSites){
                    System.out.println(key);
                }
                System.out.println();
            } else if(command.toLowerCase().equals("cached")){
                System.out.println("\nCurrently Cached Sites");
                for(String key : cache.keySet()){
                    System.out.println(key);
                }
                System.out.println();
            } else if(command.equals("close")){
                running = false;
                closeServer();
            } else {
                blockedSites.add(command);
                System.out.println("\n" + command + " blocked successfully \n");
            }
        }
        scanner.close();
    }

}
