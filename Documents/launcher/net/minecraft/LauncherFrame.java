package net.minecraft;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import javax.swing.JOptionPane;

public class LauncherFrame extends Frame
{

    private static final long serialVersionUID = 1L;
    public Map<String, String> customParameters = new HashMap<String, String>();
    public Launcher launcher;
    public LoginForm loginForm;
    private static String[] args;
    private String username;
    private String password;

    public LauncherFrame()
    {
        super("Лаунчер Minecraft-Moscow");

        setBackground(Color.BLACK);
        loginForm = new LoginForm(this);
        JPanel p = new JPanel();
        p.setLayout(new BorderLayout());
        p.add(loginForm, "Center");

        p.setPreferredSize(new Dimension(854, 480));

        setLayout(new BorderLayout());
        add(p, "Center");

        pack();
        setLocationRelativeTo(null);
        try
        {
            setIconImage(ImageIO.read(LauncherFrame.class.getResource("favicon.png")));
        }
        catch (IOException e1)
        {
            e1.printStackTrace();
        }

        addWindowListener(new WindowAdapter()
        {

            public void windowClosing(WindowEvent arg0)
            {
                new Thread()
                {

                    public void run()
                    {
                        try
                        {
                            Thread.sleep(30000L);
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                        System.out.println("FORCING EXIT!");
                        System.exit(0);
                    }
                }.start();
                if (launcher != null)
                {
                    launcher.stop();
                    launcher.destroy();
                }
                System.exit(0);
            }
        });
    }

    public void playCached(String userName)
    {
        try
        {
            if ((userName == null) || (userName.length() <= 0))
                userName = "Player";
            launcher = new Launcher();
            launcher.customParameters.putAll(customParameters);
            launcher.customParameters.put("userName", userName);
            launcher.init();
            removeAll();
            add(launcher, "Center");
            validate();
            launcher.start();
            loginForm = null;
            setTitle("Minecraft");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            showError(e.toString());
        }
    }

//--------------------------------
//  public String getFakeResult(String userName) {
//	    return Util.getFakeLatestVersion() + ":35b9fd01865fda9d70b157e244cf801c:" + userName + ":12345:";
//	  }
//---------------------------------
    public static String MD5(String md5)
    {
        try
        {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("md5");
            byte[] array = md.digest(md5.getBytes());
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < array.length; ++i)
                sb.append(Integer.toHexString((array[i] & 0xFF) | 0x100).substring(1, 3));
            return sb.toString();
        }
        catch (java.security.NoSuchAlgorithmException e)
        {
        }
        return null;
    }

    public void login(String userName, String password)
    {
        this.username = userName;
        this.password = password;
        try
        {
            String parameters = "user=" + URLEncoder.encode(userName, "UTF-8") + "&password=" + URLEncoder.encode(password, "UTF-8") + "&version=" + GameUpdater.LAUNCHER_VERSION + "&hash=" + MD5(InetAddress.getLocalHost().getHostName() + System.getProperty("user.name"));
            String result = Util.excutePost("http://www.minecraft-moscow.ru/prox/auth/auth.php", parameters);
//      String result = getFakeResult(userName);
            if (result == null)
            {
                showError("Невозможно подключится к серверу!");
                loginForm.setNoNetwork();
                return;
            }
            if (!result.contains(":"))
            {
                if (result.trim().equals("Bad login"))
                    showError("Неправильный логин или пароль!");
                else if (result.trim().equals("Old version"))
                {
                    loginForm.setOutdated();
                    showError("Нужно обновить лаунчер!");
                }
                else
                    showError(result);
                loginForm.setNoNetwork();
                return;
            }
            String[] values = result.split(":");

            launcher = new Launcher();
            launcher.customParameters.putAll(customParameters);
            launcher.customParameters.put("userName", values[2].trim());
            launcher.customParameters.put("latestVersion", values[0].trim());
            launcher.customParameters.put("downloadTicket", values[1].trim());
            launcher.customParameters.put("sessionId", values[3].trim());
            launcher.init();

            removeAll();
            add(launcher, "Center");
            validate();
            launcher.start();
            loginForm.loginOk();
            loginForm = null;
            setTitle("Minecraft");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            showError(e.toString());
            loginForm.setNoNetwork();
        }
    }

    private void showError(String error)
    {
        removeAll();
        add(loginForm);
        loginForm.setError(error);
        validate();
    }

    public boolean canPlayOffline(String userName)
    {
        Launcher launcher = new Launcher();
        launcher.customParameters.putAll(customParameters);
        launcher.init(userName, null, null, null);
        return launcher.canPlayOffline();
    }

    public static void main(String[] args)
    {
        LauncherFrame.args = args;
        try
        {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception localException)
        {
        }
        LauncherFrame launcherFrame = new LauncherFrame();
        launcherFrame.setVisible(true);
        launcherFrame.customParameters.put("stand-alone", "true");

        if (args.length >= 3)
        {
            String ip = args[2];
            String port = "25565";
            if (ip.contains(":"))
            {
                String[] parts = ip.split(":");
                ip = parts[0];
                port = parts[1];
            }

            launcherFrame.customParameters.put("server", ip);
            launcherFrame.customParameters.put("port", port);
        }

        if (args.length >= 1)
        {
            launcherFrame.loginForm.userName.setText(args[0]);
            if (args.length >= 2)
            {
                launcherFrame.loginForm.password.setText(args[1]);
                launcherFrame.loginForm.doLogin();
            }
        }
    }

    public void updateLauncher()
    {
        try
        {
            String pathToJar = MinecraftLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            String ext = pathToJar.substring(pathToJar.lastIndexOf('.') + 1);

            JOptionPane.showMessageDialog(launcher, "Ваш лаунчер устарел. \nЧтобы загрузить новый, нажмите ОК.");
            URL launcherURL = new URL("http://www.minecraft-moscow.ru/files/Launcher." + ext);
            InputStream is = launcherURL.openStream();
            FileOutputStream fos = new FileOutputStream(pathToJar);
            int len;
            byte[] buf = new byte[0x1000];
            while ((len = is.read(buf)) > 0)
                fos.write(buf, 0, len);
            is.close();
            fos.close();
            JOptionPane.showMessageDialog(launcher, "Новый лаунчер загружен. \nПосле обновления игра запустится автоматически.");
            ArrayList<String> params = new ArrayList<String>();
            if ("exe".equalsIgnoreCase(ext))
                params.add(pathToJar);
            else
            {

                params.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
                params.add("-Dsun.java2d.noddraw=true");
                params.add("-Dsun.java2d.d3d=false");
                params.add("-Dsun.java2d.opengl=false");
                params.add("-Dsun.java2d.pmoffscreen=false");
                params.add("-classpath");
                params.add(pathToJar);
                params.add("net.minecraft.LauncherFrame");
            }
            params.add(username);
            params.add(password);

            ProcessBuilder pb = new ProcessBuilder(params);
            Process process = pb.start();
            if (process == null)
                throw new Exception("!");
            System.exit(0);
        }
        catch (Exception e)
        {
            StringWriter sw = new StringWriter();
            PrintWriter ps = new PrintWriter(sw);
            e.printStackTrace(ps);
            JOptionPane.showMessageDialog(launcher, sw.toString());
        }
    }
}