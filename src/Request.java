import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class Request implements Runnable {

    Socket clientSocket;

    BufferedReader proxyToClientBr;
    BufferedWriter proxyToClientBw;
    private Thread httpsClientToServer;

    public Request(Socket clientSocket){
        this.clientSocket = clientSocket;
        try{
            proxyToClientBr = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            proxyToClientBw = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {

        String requestString;
        String line;
        String host = null;
        StringBuilder headStr = new StringBuilder();
        try{
            requestString = proxyToClientBr.readLine();
            headStr.append(requestString + "\r\n");
            while ((line=proxyToClientBr.readLine())!=null){
                headStr.append(line + "\r\n");
                if (line.length() == 0) {
                    break;
                } else {
                    String[] temp = line.split(" ");
                    if (temp[0].contains("Host")) {
                        host = temp[1];
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Error reading request from client");
            return;
        }

        System.out.println("Reuest Received " + requestString);
        String request = requestString.substring(0,requestString.indexOf(' '));

        if(request.contains("CONNECT")){
            return;
        }
        String urlString = requestString.substring(requestString.indexOf(' ')+1);

        urlString = urlString.substring(0, urlString.indexOf(' '));

        if(!urlString.substring(0,4).equals("http")){
            String temp = "http://";
            urlString = temp + urlString;
        }

        // 检查网站是否被禁
        if(Proxy.isSiteBlocked(urlString)){
            System.out.println("Blocked site requested : " + urlString);
            blockedSiteRequested();
            return;
        }

        if(Proxy.isIpBlocked(clientSocket.getInetAddress().getHostAddress())){
            System.out.println("该IP禁止访问 : " + urlString);
            return;
        }

        if(Proxy.isFishing(urlString)){
            String newUrl = Proxy.fishingTo(urlString);
            System.out.println("已跳转到钓鱼网站" + newUrl);
            System.out.println("原网站 " + urlString);
            String newBuffer = "HTTP/1.1 302 Moved Temporarily\r\n" +
                    "Location: " + newUrl +"\r\n\r\n";
            try {
                clientSocket.getOutputStream().write(newBuffer.getBytes());
                clientSocket.getOutputStream().flush();
                return;
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        if(request.equals("CONNECT")){
            System.out.println("HTTPS Request for : " + urlString + "\n");
            handleHTTPSRequest(urlString);
        } else{
            Cache cache;
            if((cache = Proxy.getCachedPage(urlString)) != null){
                System.out.println("发现了缓存 : " + urlString + "\n");
                sendCachedPageToClient(cache, headStr, urlString, host);
            } else {
                System.out.println("HTTP GET for : " + urlString + "\n");
                sendNonCachedToClient(urlString, headStr, host);
            }
        }
    }


    private void sendCachedPageToClient(Cache cache, StringBuilder headStr, String urlString, String host){

        File cachedFile = cache.file;
        //根据host头解析出目标服务器的host和port
        String[] hostTemp = host.split(":");
        String server_host = hostTemp[0];
        int port = 80;
        if (hostTemp.length > 1) {
            port = Integer.valueOf(hostTemp[1]);
        }

        try{

            Socket socket = new Socket(server_host, port);
            OutputStream serverOutput = socket.getOutputStream();

            String requset = "GET " + urlString +" HTTP/1.1\r\n" +
                    "Host: " + host + "\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36\r\n" +
                    "If-Modified-Since: " + cache.last_date + "\r\n" +
                    "\r\n";
            serverOutput.write(requset.getBytes());
            serverOutput.flush();

            BufferedReader serverBufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String firstLine = serverBufferedReader.readLine();
            if(firstLine.contains("304")){
                System.out.println("Cache 没有改变 " + cachedFile.getName());

                FileInputStream stream = new FileInputStream(cachedFile);
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                byte[] buff=new byte[1024];
                int len=1;
                while ((len=stream.read(buff))!=-1){//如果返回-1，则表示读到了输入流到了结尾
                    buffer.write(buff,0,len);
                }

                System.out.println(new String(buffer.toByteArray()));
                clientSocket.getOutputStream().write(buffer.toByteArray());
                clientSocket.getOutputStream().flush();
            }else {
                System.out.println("Cahce 发生了改变从 " + cache.last_date);
                sendNonCachedToClient(urlString, headStr, host);
            }

        } catch (IOException e) {
            System.out.println("Error Sending Cached file to client");
            e.printStackTrace();
        }
    }


    private void sendNonCachedToClient(String urlString, StringBuilder headStr, String host){

        //根据host头解析出目标服务器的host和port
        String[] hostTemp = host.split(":");
        String server_host = hostTemp[0];
        int port = 80;
        if (hostTemp.length > 1) {
            port = Integer.valueOf(hostTemp[1]);
        }

        try{
            int fileExtensionIndex = urlString.lastIndexOf(".");
            String fileExtension;
            fileExtension = urlString.substring(fileExtensionIndex, urlString.length());
            String fileName = urlString.substring(0,fileExtensionIndex);
            fileName = fileName.substring(fileName.indexOf('.')+1);
            fileName = fileName.replace("/", "__");
            fileName = fileName.replace('.','_');
            if(fileExtension.contains("/")){
                fileExtension = fileExtension.replace("/", "__");
                fileExtension = fileExtension.replace('.','_');
                fileExtension += ".html";
            }

            fileName = fileName + fileExtension;

            boolean caching = true;
            File fileToCache = null;

            FileOutputStream fileToCacheBW = null;
            String last_modified_date = null;
            try{
                fileToCache = new File("cached/" + fileName);
                if(!fileToCache.exists()){
                    fileToCache.createNewFile();
                }

                fileToCacheBW = new FileOutputStream(fileToCache);
            } catch (IOException e){
                System.out.println("Couldn't cache: " + fileName);
                caching = false;
                e.printStackTrace();
            } catch (NullPointerException e) {
                System.out.println("NPE opening file");
            }

            Socket serverSocket = new Socket(server_host, port);
            OutputStream serverOutput = serverSocket.getOutputStream();
            InputStream serverInput = serverSocket.getInputStream();

            serverOutput.write(headStr.toString().getBytes());
            serverOutput.flush();

            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            String charset = "UTF-8";
            byte[] buff=new byte[1024];
            int len=1;
            while ((len=serverInput.read(buff))!=-1){//如果返回-1，则表示读到了输入流到了结尾
                buffer.write(buff,0,len);
            }

            clientSocket.getOutputStream().write(buffer.toByteArray());
            clientSocket.getOutputStream().flush();

            if(caching){
                String data = new String(buffer.toByteArray(), charset);
                BufferedReader reader = new BufferedReader(new StringReader(data));
                String line;
                while((line = reader.readLine()) != null){
                    if (line.contains("Date")){
                        last_modified_date = line.substring(6);
//                        System.out.println("发送时间 " + last_modified_date);
                        break;
                    }
                }

                fileToCacheBW.write(buffer.toByteArray());
                fileToCacheBW.flush();
                Cache cache = new Cache(fileToCache, last_modified_date);
                Proxy.addCachedPage(urlString, cache);
            }

            if(fileToCacheBW != null){
                fileToCacheBW.close();
            }

            if(proxyToClientBw != null){
                proxyToClientBw.close();
            }
        }

        catch (Exception e){
            e.printStackTrace();
        }
    }


    private void handleHTTPSRequest(String urlString){
        String url = urlString.substring(7);
        String pieces[] = url.split(":");
        url = pieces[0];
        int port  = Integer.valueOf(pieces[1]);

        try{
            for(int i=0;i<5;i++){
                proxyToClientBr.readLine();
            }

            InetAddress address = InetAddress.getByName(url);
            Socket proxyToServerSocket = new Socket(address, port);
            proxyToServerSocket.setSoTimeout(5000);

            String line = "HTTP/1.1 200 Connection established\r\n" +
                    "Proxy-Agent: ProxyServer/1.0\r\n" +
                    "\r\n";
            proxyToClientBw.write(line);
            proxyToClientBw.flush();

            BufferedWriter proxyToServerBW = new BufferedWriter(new OutputStreamWriter(proxyToServerSocket.getOutputStream()));
            BufferedReader proxyToServerBR = new BufferedReader(new InputStreamReader(proxyToServerSocket.getInputStream()));

            ClientToServerHttpsTransmit clientToServerHttps =
                    new ClientToServerHttpsTransmit(clientSocket.getInputStream(), proxyToServerSocket.getOutputStream());
            httpsClientToServer = new Thread(clientToServerHttps);
            httpsClientToServer.start();

            try {
                byte[] buffer = new byte[4096];
                int read;
                do {
                    read = proxyToServerSocket.getInputStream().read(buffer);
                    if (read > 0) {
                        clientSocket.getOutputStream().write(buffer, 0, read);
                        if (proxyToServerSocket.getInputStream().available() < 1) {
                            clientSocket.getOutputStream().flush();
                        }
                    }
                } while (read >= 0);

            } catch (SocketTimeoutException e) {

            } catch (IOException e) {
                e.printStackTrace();
            }

            if(proxyToServerSocket != null){
                proxyToServerSocket.close();
            }

            if(proxyToServerBR != null){
                proxyToServerBR.close();
            }

            if(proxyToServerBW != null){
                proxyToServerBW.close();
            }

            if(proxyToClientBw != null){
                proxyToClientBw.close();
            }
        } catch (SocketTimeoutException e) {
            String line = "HTTP/1.0 504 Timeout Occured after 10s\n" +
                    "User-Agent: ProxyServer/1.0\n" +
                    "\r\n";
            try{
                proxyToClientBw.write(line);
                proxyToClientBw.flush();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        } catch (Exception e){
            System.out.println("Error on HTTPS : " + urlString );
            e.printStackTrace();
        }
    }


    class ClientToServerHttpsTransmit implements Runnable{

        InputStream proxyToClientIS;
        OutputStream proxyToServerOS;

        public ClientToServerHttpsTransmit(InputStream proxyToClientIS, OutputStream proxyToServerOS) {
            this.proxyToClientIS = proxyToClientIS;
            this.proxyToServerOS = proxyToServerOS;
        }

        @Override
        public void run(){
            try {
                byte[] buffer = new byte[4096];
                int read;
                do {
                    read = proxyToClientIS.read(buffer);
                    if (read > 0) {
                        proxyToServerOS.write(buffer, 0, read);
                        if (proxyToClientIS.available() < 1) {
                            proxyToServerOS.flush();
                        }
                    }
                } while (read >= 0);
            }
            catch (SocketTimeoutException ste) {
                // TODO: handle exception
            }
            catch (IOException e) {
                System.out.println("Proxy to client HTTPS read timed out");
                e.printStackTrace();
            }
        }
    }

    private void blockedSiteRequested(){
        try {
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
            String line = "HTTP/1.0 403 Access Forbidden \n" +
                    "User-Agent: ProxyServer/1.0\n" +
                    "\r\n";
            bufferedWriter.write(line);
            bufferedWriter.flush();
        } catch (IOException e) {
            System.out.println("Error writing to client when requested a blocked site");
            e.printStackTrace();
        }
    }
}




