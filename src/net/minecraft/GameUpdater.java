package net.minecraft;

import java.applet.Applet;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class GameUpdater
        implements Runnable, Constants
{

    public static final int STATE_INIT = 1;
    public static final int STATE_DETERMINING_PACKAGES = 2;
    public static final int STATE_CHECKING_CACHE = 3;
    public static final int STATE_DOWNLOADING = 4;
    public static final int STATE_EXTRACTING_PACKAGES = 5;
    public static final int STATE_UPDATING_CLASSPATH = 6;
    public static final int STATE_SWITCHING_APPLET = 7;
    public static final int STATE_INITIALIZE_REAL_APPLET = 8;
    public static final int STATE_START_REAL_APPLET = 9;
    public static final int STATE_DONE = 10;
    public static final int STATE_GENERATING_MD5S = 11;
    public boolean oldLauncher = false;
    public int percentage;
    public int subPercentage;
    public int currentSizeExtract;
    public int totalSizeExtract;
    protected URL[] urlList;
    protected String[] md5s;
    protected String[] relativeUrls;
    protected int[] fileSizes;
    private static ClassLoader classLoader;
    protected Thread loaderThread;
    protected Thread animationThread;
    public boolean fatalError;
    public String fatalErrorDescription;
    protected String subtaskMessage = "";
    protected int state = STATE_INIT;
    protected String[] genericErrorMessage =
    {
        "An error occured while loading the applet.", "Please contact support to resolve this issue.", "<placeholder for error message>"
    };
    protected boolean certificateRefused;
    protected String[] certificateRefusedMessage =
    {
        "Permissions for Applet Refused.", "Please accept the permissions dialog to allow", "the applet to continue the loading process."
    };
    protected static boolean natives_loaded = false;
    public static boolean forceUpdate = false;
    private String latestVersion;
    private String mainGameUrl;
    public boolean pauseAskUpdate;
    public boolean shouldUpdate;

    public GameUpdater(String latestVersion, String mainGameUrl)
    {
        this.latestVersion = latestVersion;
        this.mainGameUrl = mainGameUrl;
    }

    public void init()
    {
        state = STATE_INIT;

    }

    private String generateStacktrace(Exception exception)
    {
        Writer result = new StringWriter();
        PrintWriter printWriter = new PrintWriter(result);
        exception.printStackTrace(printWriter);
        return result.toString();
    }

    protected String getDescriptionForState()
    {
        switch (state)
        {
            case STATE_INIT:
                return "Инициализация загрузчика";
            case STATE_DETERMINING_PACKAGES:
                return "Обнаружение пакетов для скачки";
            case STATE_CHECKING_CACHE:
                return "Проверка кеш-файлов";
            case STATE_DOWNLOADING:
                return "Скачивание пакетов";
            case STATE_EXTRACTING_PACKAGES:
                return "Извлечение скачанных пакетов";
            case STATE_UPDATING_CLASSPATH:
                return "Обновление путей";
            case STATE_SWITCHING_APPLET:
                return "Сворачивание апплета";
            case STATE_INITIALIZE_REAL_APPLET:
                return "Инициализация реального апплета";
            case STATE_START_REAL_APPLET:
                return "Старт реального апплета";
            case STATE_DONE:
                return "Загрузка завершена";
            case STATE_GENERATING_MD5S:
                return "Проверка файлов на изменения";

        }
        return "Неизвестное положение";
    }

    protected void loadJarURLs() throws Exception
    {
        state = STATE_DETERMINING_PACKAGES;

        Properties jars = new Properties();
        try
        {
            jars.load(new URL(DOWNLOAD_FOLDER + "md5s").openStream());
            jars.store(new FileOutputStream(new File(Util.getWorkingDirectory(), "md5s")), "");
        }
        catch (Exception e)
        {
            jars.load(new FileInputStream(new File(Util.getWorkingDirectory(), "md5s")));
        }
        int jarCount = jars.size() + 1;

        urlList = new URL[jarCount];
        md5s = new String[jarCount];
        relativeUrls = new String[jarCount];
        fileSizes = new int[jarCount];

        //# Откуда скачивать
        URL path = new URL(DOWNLOAD_FOLDER);
        int i = 0;
        for (Map.Entry entry : jars.entrySet())
        {
            String[] split = ((String) entry.getValue()).trim().split(";");
            md5s[i] = split[0];
            fileSizes[i] = Integer.parseInt(split[1]);
            relativeUrls[i] = ((String) entry.getKey()).replace("/", File.separator);
            urlList[i++] = new URL(path, ((String) entry.getKey()).substring(1).replace(" ", "%20"));
        }

        String osName = System.getProperty("os.name");
        String nativeJar = null;

        if (osName.startsWith("Win"))
            nativeJar = "windows_natives.jar";
        else if (osName.startsWith("Linux"))
            nativeJar = "linux_natives.jar";
        else if (osName.startsWith("Mac"))
            nativeJar = "macosx_natives.jar";
        else if ((osName.startsWith("Solaris")) || (osName.startsWith("SunOS")))
            nativeJar = "solaris_natives.jar";
        else
            fatalErrorOccured("OS (" + osName + ") не поддерживается", null);

        if (nativeJar == null)
            fatalErrorOccured("lwjgl файлы не найдены", null);
        else
        {
            md5s[i] = "";
            relativeUrls[i] = File.separator + "bin" + File.separator + nativeJar;
            urlList[i] = new URL(path, nativeJar);
            fileSizes[i] = urlList[i].openConnection().getContentLength();
        }
    }

    @Override
    public void run()
    {
        init();
        state = STATE_CHECKING_CACHE;

        percentage = 5;
        try
        {

            loadJarURLs();

            File dir = Util.getWorkingDirectory();
            File binDir = new File(dir, "bin");

            if (!binDir.exists())
                binDir.mkdirs();

            if (latestVersion != null)
            {
                File versionFile = new File(binDir, "version");

                boolean cacheAvailable = false;
                if ((!forceUpdate) && (versionFile.exists()) && ((latestVersion.equals("-1")) || (latestVersion.equals(readVersionFile(versionFile)))))
                {
                    cacheAvailable = true;
                    percentage = 90;
                }

                if ((forceUpdate) || (!cacheAvailable))
                {
                    shouldUpdate = true;
                    if ((!forceUpdate) && (versionFile.exists()))
                        checkShouldUpdate();
                    if (shouldUpdate)
                    {
                        writeVersionFile(versionFile, "");
                        String binPath = binDir.getCanonicalPath();
                        String path = dir.getCanonicalPath();
                        downloadJars(path);
                        extractJars(binPath);
                        extractNatives(binPath);
                        if (latestVersion != null)
                        {
                            percentage = 90;
                            writeVersionFile(versionFile, latestVersion);
                        }
                    }
                    else
                        percentage = 90;
                }
            }

            updateClassPath(binDir);
            state = STATE_DONE;
        }
        catch (AccessControlException ace)
        {
            fatalErrorOccured(ace.getMessage(), ace);
            certificateRefused = true;
        }
        catch (Exception e)
        {
            fatalErrorOccured(e.getMessage(), e);
        }
        finally
        {
            loaderThread = null;
        }
    }

    private void checkShouldUpdate()
    {
        pauseAskUpdate = true;
        while (pauseAskUpdate)
            try
            {
                Thread.sleep(1000L);
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
    }

    protected String readVersionFile(File file) throws Exception
    {
        DataInputStream dis = new DataInputStream(new FileInputStream(file));
        String version = dis.readUTF();
        dis.close();
        return version;
    }

    protected void writeVersionFile(File file, String version) throws Exception
    {
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(file));
        dos.writeUTF(version);
        dos.close();
    }

    protected void updateClassPath(File dir)
            throws Exception
    {
        state = STATE_UPDATING_CLASSPATH;

        percentage = 95;

        URL[] urls = new URL[urlList.length];
        for (int i = 0; i < urlList.length; i++)
            urls[i] = new File(dir, getJarName(urlList[i])).toURI().toURL();

        if (classLoader == null)
            classLoader = new URLClassLoader(urls)
            {

                @Override
                protected PermissionCollection getPermissions(CodeSource codesource)
                {
                    PermissionCollection perms = null;
                    try
                    {
                        Method method = SecureClassLoader.class.getDeclaredMethod("getPermissions", new Class[]
                                {
                                    CodeSource.class
                                });

                        method.setAccessible(true);
                        perms = (PermissionCollection) method.invoke(getClass().getClassLoader(), new Object[]
                                {
                                    codesource
                                });

                        String host = "www.minecraft-moscow.ru";

                        if ((host != null) && (host.length() > 0))
                            perms.add(new SocketPermission(host, "connect,accept"));
                        else
                            codesource.getLocation().getProtocol().equals("file");

                        perms.add(new FilePermission("<<ALL FILES>>", "read"));
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    return perms;
                }
            };
        String path = dir.getAbsolutePath();
        if (!path.endsWith(File.separator))
            path = path + File.separator;
        unloadNatives(path);

        System.setProperty("org.lwjgl.librarypath", path + "natives");
        System.setProperty("net.java.games.input.librarypath", path + "natives");

        natives_loaded = true;
    }

    private void unloadNatives(String nativePath)
    {
        if (!natives_loaded)
            return;
        try
        {
            Field field = ClassLoader.class.getDeclaredField("loadedLibraryNames");
            field.setAccessible(true);
            Vector<?> libs = (Vector<?>) field.get(getClass().getClassLoader());

            String path = new File(nativePath).getCanonicalPath();

            for (int i = 0; i < libs.size(); i++)
            {
                String s = (String) libs.get(i);

                if (s.startsWith(path))
                {
                    libs.remove(i);
                    i--;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public Applet createApplet() throws ClassNotFoundException, InstantiationException, IllegalAccessException
    {
        Class<?> appletClass = classLoader.loadClass("net.minecraft.client.MinecraftApplet");
        return (Applet) appletClass.newInstance();
    }

    protected void downloadJars(String path)
            throws Exception
    {



        state = STATE_DOWNLOADING;

        int initialPercentage = this.percentage = 10;

        byte[] buffer = new byte[65536];
        for (int i = 0; i < urlList.length; i++)
        {
            boolean downloadFile = true;

            while (downloadFile)
            {
                downloadFile = false;

                String currentFile = relativeUrls[i];
                String curFilePath = path + currentFile;
                File curFile = new File(curFilePath.replace("/", File.separator));
                File curFileDir = new File(curFile.getParent());
                curFileDir.mkdirs();
                state = STATE_GENERATING_MD5S;
                boolean skip;
                if (curFile.createNewFile())
                    skip = false;
                else if (fileSizes[i] != curFile.length())
                    skip = false;
                else
                {
                    MessageDigest md = MessageDigest.getInstance("MD5");
                    InputStream is = new FileInputStream(curFile);
                    byte[] buf = new byte[0xffff];
                    int checked = 0;
                    int len;
                    while ((len = is.read(buf)) > 0)
                    {
                        checked += len;
                        md.update(buf, 0, len);
                        subPercentage = checked * 50 / fileSizes[i];
                        subtaskMessage = ("Проверка: " + currentFile + " " + percentage + "%");
                    }
                    String md5 = new BigInteger(1, md.digest()).toString(16);
                    if (md5.equals(md5s[i]))
                        skip = true;
                    else
                        skip = false;
                }
                percentage = (initialPercentage + i * 75 / urlList.length);
                if (skip)
                    break;

                URLConnection urlconnection = urlList[i].openConnection();

                if ((urlconnection instanceof HttpURLConnection))
                {
                    urlconnection.setRequestProperty("Cache-Control", "no-cache");

                    urlconnection.connect();
                }
                InputStream inputstream = getJarInputStream(currentFile, urlconnection);

                FileOutputStream fos = new FileOutputStream(curFile);

                long downloadStartTime = System.currentTimeMillis();
                int downloadedAmount = 0;
                int fileSize = 0;
                String downloadSpeedMessage = "";

                int bufferSize;
                state = STATE_DOWNLOADING;
                while ((bufferSize = inputstream.read(buffer, 0, buffer.length)) != -1)
                {
                    //int bufferSize;
                    fos.write(buffer, 0, bufferSize);

                    fileSize += bufferSize;
                    subPercentage = 50 + fileSize * 50 / fileSizes[i];
                    subtaskMessage = ("Загрузка: " + currentFile + " " + percentage + "%");

                    downloadedAmount += bufferSize;
                    long timeLapse = System.currentTimeMillis() - downloadStartTime;

                    if (timeLapse >= 1000L)
                    {
                        float downloadSpeed = downloadedAmount / (float) timeLapse;
                        downloadSpeed = (int) (downloadSpeed * 100.0F) / 100.0F;
                        downloadSpeedMessage = " @ " + downloadSpeed + " KB/sec";
                        downloadedAmount = 0;
                        downloadStartTime += 1000L;
                    }

                    subtaskMessage += downloadSpeedMessage;
                }

                inputstream.close();
                fos.close();
            }
        }

        // subtaskMessage = "";
    }

    protected InputStream getJarInputStream(String currentFile, final URLConnection urlconnection)
            throws Exception
    {
        final InputStream[] is = new InputStream[1];

        for (int j = 0; (j < 3) && (is[0] == null); j++)
        {
            Thread t = new Thread()
            {

                @Override
                public void run()
                {
                    try
                    {
                        is[0] = urlconnection.getInputStream();
                    }
                    catch (IOException localIOException)
                    {
                        localIOException.printStackTrace();
                    }
                }
            };
            t.setName("JarInputStreamThread");
            t.start();

            int iterationCount = 0;
            while ((is[0] == null) && (iterationCount++ < 5))
                try
                {
                    t.join(1000L);
                }
                catch (InterruptedException localInterruptedException)
                {
                }
            if (is[0] != null)
                continue;
            try
            {
                t.interrupt();
                t.join();
            }
            catch (InterruptedException localInterruptedException1)
            {
            }
        }

        if (is[0] == null)
            throw new Exception("Unable to download " + currentFile);

        return is[0];
    }

    /*
     * protected void extractLZMA(String in, String out) throws Exception { File
     * f = new File(in); if (!f.exists()) return; FileInputStream
     * fileInputHandle = new FileInputStream(f); Class<?> clazz =
     * Class.forName("LZMA.LzmaInputStream"); Constructor<?> constructor =
     * clazz.getDeclaredConstructor(new Class[] { InputStream.class });
     *
     * InputStream inputHandle = (InputStream)constructor.newInstance(new
     * Object[] { fileInputHandle });
     *
     * OutputStream outputHandle = new FileOutputStream(out);
     *
     * byte[] buffer = new byte[16384];
     *
     * int ret = inputHandle.read(buffer); while (ret >= 1) {
     * outputHandle.write(buffer, 0, ret); ret = inputHandle.read(buffer); }
     *
     * inputHandle.close(); outputHandle.close();
     *
     * outputHandle = null; inputHandle = null;
     *
     * f.delete(); }
     */
// from AnjoCaido launcher  
    protected void extractJars(String path)
            throws Exception
    {
        state = STATE_EXTRACTING_PACKAGES;
    }

    protected void extractNatives(String path) throws Exception
    {
        state = STATE_EXTRACTING_PACKAGES;
        System.out.println(path);
        int initialPercentage = percentage;

        String nativeJar = getJarName(urlList[(urlList.length - 1)]);

        Certificate[] certificate = Launcher.class.getProtectionDomain().getCodeSource().getCertificates();

        if (certificate == null)
        {
            URL location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();

            JarURLConnection jurl = (JarURLConnection) new URL("jar:" + location.toString() + "!/net/minecraft/Launcher.class").openConnection();
            jurl.setDefaultUseCaches(true);
            try
            {
                certificate = jurl.getCertificates();
            }
            catch (Exception localException)
            {
            }
        }
        File nativeFolder = new File(path + File.separator + "natives");
        if (!nativeFolder.exists())
            nativeFolder.mkdir();

        File file = new File(path + File.separator + nativeJar);
        if (!file.exists())
            return;
        JarFile jarFile = new JarFile(file, true);
        Enumeration<?> entities = jarFile.entries();

        totalSizeExtract = 0;

        while (entities.hasMoreElements())
        {
            JarEntry entry = (JarEntry) entities.nextElement();

            if ((entry.isDirectory()) || (entry.getName().indexOf('/') != -1))
                continue;
            totalSizeExtract = (int) (totalSizeExtract + entry.getSize());
        }

        currentSizeExtract = 0;

        entities = jarFile.entries();

        while (entities.hasMoreElements())
        {
            JarEntry entry = (JarEntry) entities.nextElement();

            if ((entry.isDirectory()) || (entry.getName().indexOf('/') != -1))
                continue;
            File f = new File(path + File.separator + "natives" + File.separator + entry.getName());
            if ((f.exists())
                && (!f.delete()))
                continue;

            InputStream in = jarFile.getInputStream(jarFile.getEntry(entry.getName()));
            OutputStream out = new FileOutputStream(path + File.separator + "natives" + File.separator + entry.getName());

            byte[] buffer = new byte[65536];
            int bufferSize;
            while ((bufferSize = in.read(buffer, 0, buffer.length)) != -1)
            {
                //int bufferSize;
                out.write(buffer, 0, bufferSize);
                currentSizeExtract += bufferSize;

                percentage = (initialPercentage + currentSizeExtract * 20 / totalSizeExtract);
                subtaskMessage = ("Extracting: " + entry.getName() + " " + currentSizeExtract * 100 / totalSizeExtract + "%");
            }

            validateCertificateChain(certificate, entry.getCertificates());

            in.close();
            out.close();
        }
        subtaskMessage = "";

        jarFile.close();

        File f = new File(path + File.separator + nativeJar);
        f.delete();
    }

    protected static void validateCertificateChain(Certificate[] ownCerts, Certificate[] native_certs)
            throws Exception
    {
        if (ownCerts == null)
            return;
        if (native_certs == null)
            throw new Exception("Unable to validate certificate chain. Native entry did not have a certificate chain at all");

        if (ownCerts.length != native_certs.length)
            throw new Exception("Unable to validate certificate chain. Chain differs in length [" + ownCerts.length + " vs " + native_certs.length + "]");

        for (int i = 0; i < ownCerts.length; i++)
            if (!ownCerts[i].equals(native_certs[i]))
                throw new Exception("Certificate mismatch: " + ownCerts[i] + " != " + native_certs[i]);
    }

    protected String getJarName(URL url)
    {
        String fileName = url.getFile();

        if (fileName.contains("?"))
            fileName = fileName.substring(0, fileName.indexOf("?"));
        if (fileName.endsWith(".pack.lzma"))
            fileName = fileName.replaceAll(".pack.lzma", "");
        else if (fileName.endsWith(".pack"))
            fileName = fileName.replaceAll(".pack", "");
        else if (fileName.endsWith(".lzma"))
            fileName = fileName.replaceAll(".lzma", "");

        return fileName.substring(fileName.lastIndexOf('/') + 1);
    }

    protected String getFileName(URL url)
    {
        String fileName = url.getFile();
        if (fileName.contains("?"))
            fileName = fileName.substring(0, fileName.indexOf("?"));
        return fileName.substring(fileName.lastIndexOf('/') + 1);
    }

    protected void fatalErrorOccured(String error, Exception e)
    {
        e.printStackTrace();
        fatalError = true;
        fatalErrorDescription = ("Fatal error occured (" + state + "): " + error);
        System.out.println(fatalErrorDescription);

        System.out.println(generateStacktrace(e));
    }

    public boolean canPlayOffline()
    {
        try
        {
            String path = (String) AccessController.doPrivileged(new PrivilegedExceptionAction<Object>()
            {

                @Override
                public Object run() throws Exception
                {
                    return Util.getWorkingDirectory() + File.separator + "bin" + File.separator;
                }
            });
            File dir = new File(path);
            if (!dir.exists())
                return false;

            dir = new File(dir, "version");
            if (!dir.exists())
                return false;

            if (dir.exists())
            {
                String version = readVersionFile(dir);
                if ((version != null) && (version.length() > 0))
                    return true;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }
        return false;
    }
}