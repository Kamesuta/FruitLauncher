/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher;

import static com.skcraft.launcher.util.SharedLocale.*;

import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.commons.lang.StringUtils;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.skcraft.launcher.auth.AccountList;
import com.skcraft.launcher.auth.LoginService;
import com.skcraft.launcher.auth.YggdrasilLoginService;
import com.skcraft.launcher.launch.LaunchSupervisor;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.swing.DefaultFont;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.update.UpdateManager;
import com.skcraft.launcher.util.HttpRequest;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.SimpleLogFormatter;

import lombok.Delegate;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.java.Log;
import net.teamfruit.skcraft.launcher.TipList;
import net.teamfruit.skcraft.launcher.dirs.ConfigLauncherDirectories;
import net.teamfruit.skcraft.launcher.dirs.LauncherDirectories;
import net.teamfruit.skcraft.launcher.discordrpc.LauncherDiscord;
import net.teamfruit.skcraft.launcher.integration.UriScheme;
import net.teamfruit.skcraft.launcher.skins.DefaultSkin;
import net.teamfruit.skcraft.launcher.skins.LocalSkin;
import net.teamfruit.skcraft.launcher.skins.RemoteSkinList;
import net.teamfruit.skcraft.launcher.skins.Skin;

/**
 * The main entry point for the launcher.
 */
@Log
public final class Launcher {

    public static final int PROTOCOL_VERSION = 2;

    public static final int DEBUG_VERSION = 1;

    @Getter
    private final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
    @Getter @Setter private Supplier<Window> mainWindowSupplier = new DefaultLauncherSupplier(this);
    @Getter private final Class<?> mainClass;
    @Getter private final LauncherArguments options;
    @Getter private final String[] args;
    @Getter private final Properties properties;
    @Getter private final InstanceList instances;
    @Getter private final TipList tips;
    @Getter private final Configuration config;
    @Getter private final AccountList accounts;
    @Getter final private RemoteSkinList remoteSkins;
    @Getter @Setter private Skin skin = new DefaultSkin(this);
    @Getter private final LaunchSupervisor launchSupervisor = new LaunchSupervisor(this);
    @Getter private final UpdateManager updateManager = new UpdateManager(this);
    @Getter private final InstanceTasks instanceTasks = new InstanceTasks(this);
    @Getter private final UriScheme uriScheme = new UriScheme(this);

    @Delegate @Getter private final LauncherDirectories directories;

    /**
     * Create a new launcher instance with the given base directory.
     * @param options
     *
     * @param argBaseDir the base directory
     * @throws java.io.IOException on load error
     */
    public Launcher(@NonNull Class<?> mainClass, @NonNull LauncherArguments options, @NonNull String[] args, @NonNull final File argBaseDir) throws IOException {
        this(mainClass, options, args, argBaseDir, argBaseDir);
    }

    /**
     * Create a new launcher instance with the given base and configuration
     * directories.
     *
     * @param argBaseDir the base directory
     * @param argConfigDir the config directory
     * @throws java.io.IOException on load error
     */
    public Launcher(@NonNull Class<?> mainClass, @NonNull LauncherArguments options, @NonNull String[] args, @NonNull final File argBaseDir, @NonNull final File argConfigDir) throws IOException {
        SharedLocale.loadBundle("com.skcraft.launcher.lang.Launcher", Locale.getDefault());

        this.mainClass = mainClass;
        this.options = options;
        this.args = args;
        this.properties = LauncherUtils.loadProperties(Launcher.class, "launcher.properties", "com.skcraft.launcher.propertiesFile");
        this.instances = new InstanceList(this);
        this.tips = new TipList(this);
        this.config = Persistence.load(new File(argConfigDir, "config.json"), Configuration.class);
        this.accounts = Persistence.load(new File(argConfigDir, "accounts.dat"), AccountList.class);
        this.directories = new ConfigLauncherDirectories(this.config) {
			@Getter private final File configDir = argConfigDir;
			@Getter private final File baseDir = argBaseDir;
		};

		LauncherDiscord.init(this.config);

        DefaultFont.configUIFont();

        setDefaultConfig();

        remoteSkins = new RemoteSkinList(this);

        LocalSkin localSkin = new LocalSkin(this, config.getSkin());
    	setSkin(localSkin.getSkin());

        SharedLocale.setBundleSupplier(new Supplier<ResourceBundle>() {
			@Override
			public ResourceBundle get() {
				return skin.getLang();
			}
		});

		SwingHelper.setSupportURL(new Supplier<URL>() {
			@Override
			public URL get() {
				try {
					return getSupportURL();
				} catch (Exception e) {
				}
				return null;
			}
		});

        if (accounts.getSize() > 0) {
            accounts.setSelectedItem(accounts.getElementAt(0));
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                cleanupExtractDir();
            }
        });

        updateManager.checkForUpdate();

        executor.submit(new Runnable() {
            @Override
            public void run() {
        		uriScheme.install();
            }
        });

        //SwingHelper.showErrorDialog(null, "["+getOptions().getUriPath()+"]", "URI Path");
        //getOptions().processURI();
    }

    /**
     * Updates any incorrect / unset configuration settings with defaults.
     */
    public void setDefaultConfig() {
        double configMax = config.getMaxMemory() / 1024.0;
        double suggestedMax = 8;
        double available = Double.MAX_VALUE;

        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
        	Class<?> clazz = Class.forName("com.sun.management.OperatingSystemMXBean");
        	long totalmemory = (long) clazz.getMethod("getTotalPhysicalMemorySize").invoke(bean);
            available = totalmemory / 1024.0 / 1024.0 / 1024.0;
            if (available <= 12) {
                suggestedMax = available * 0.65;
            } else {
                suggestedMax = 8;
            }
        } catch (Exception ignored) {
        }

        if (config.getMaxMemory() <= 0 || configMax >= available - 1) {
            config.setMaxMemory((int) (suggestedMax * 1024));
        }

        String edition = options.getEdition();
        if (StringUtils.isEmpty(config.getSkin()))
        	if (!StringUtils.isEmpty(edition))
        		config.setSkin(edition);
        	else
        		config.setSkin("-");
    }

    /**
     * Get the launcher version.
     *
     * @return the launcher version
     */
    public String getVersion() {
        String version = getProperties().getProperty("version");
        if (version.equals("${project.version}")) {
            return "1.0.0-SNAPSHOT";
        }
        return version;
    }

    /**
     * Get a login service.
     *
     * @return a login service
     */
    public LoginService getLoginService() {
        return new YggdrasilLoginService(HttpRequest.url(getProperties().getProperty("yggdrasilAuthUrl")));
    }

    /**
     * Get a assets root.
     *
     * @return a assets root
     */
    public AssetsRoot getAssets() {
        return new AssetsRoot(getAssetsDir());
    }

	/**
     * Get the skins URL.
     *
     * @return the skins URL
     */
    public URL getSkinsURL() {
        try {
            return HttpRequest.url(
                    String.format(getProperties().getProperty("skinsUrl"),
                            URLEncoder.encode(getVersion(), "UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
	}

	/**
     * Get the news URL.
     *
     * @return the news URL
     */
    public URL getNewsURL() {
        try {
            return HttpRequest.url(
                    String.format(getSkin().getNewsURL(),
                            URLEncoder.encode(getVersion(), "UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the tips URL.
     *
     * @return the tips URL
     */
    public URL getTipsURL() {
        try {
            return HttpRequest.url(
                    String.format(getSkin().getTipsURL(),
                            URLEncoder.encode(getVersion(), "UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the support URL.
     *
     * @return the support URL
     */
    public URL getSupportURL() {
        try {
            return HttpRequest.url(
                    String.format(getSkin().getSupportURL(),
                            URLEncoder.encode(getVersion(), "UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the packages URL.
     *
     * @return the packages URL
     */
    public URL getPackagesURL() {
        try {
            String key = Strings.nullToEmpty(getConfig().getGameKey());
            return HttpRequest.url(
                    String.format(getProperties().getProperty("packageListUrl"),
                            URLEncoder.encode(key, "UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convenient method to fetch a property.
     *
     * @param key the key
     * @return the property
     */
    public String prop(String key) {
        return getProperties().getProperty(key);
    }

    /**
     * Convenient method to fetch a property.
     *
     * @param key the key
     * @param args formatting arguments
     * @return the property
     */
    public String prop(String key, String... args) {
        return String.format(getProperties().getProperty(key), (Object[]) args);
    }

    /**
     * Convenient method to fetch a property.
     *
     * @param key the key
     * @return the property
     */
    public URL propUrl(String key) {
        return HttpRequest.url(prop(key));
    }

    /**
     * Convenient method to fetch a property.
     *
     * @param key the key
     * @param args formatting arguments
     * @return the property
     */
    public URL propUrl(String key, String... args) {
        return HttpRequest.url(prop(key, args));
    }

    /**
     * Show the launcher.
     */
    public void showLauncherWindow() {
        mainWindowSupplier.get().setVisible(true);
    }

    /**
     * Create a new launcher from arguments.
     *
     * @param args the arguments
     * @return the launcher
     * @throws ParameterException thrown on a bad parameter
     * @throws IOException throw on an I/O error
     */
    public static Launcher createFromArguments(@NonNull Class<?> mainClass, String[] args) throws ParameterException, IOException {
        LauncherArguments options = new LauncherArguments();
        JCommander cmd = new JCommander(options);
        cmd.setAcceptUnknownOptions(true);
        cmd.parse(args);

        Integer bsVersion = options.getBootstrapVersion();
        log.info(bsVersion != null ? "Bootstrap version " + bsVersion + " detected" : "Not bootstrapped");

        File dir = options.getDir();
        if (dir != null) {
            log.info("Using given base directory " + dir.getAbsolutePath());
        } else {
            dir = new File(".");
            log.info("Using current directory " + dir.getAbsolutePath());
        }

        return new Launcher(mainClass, options, args, dir);
    }

    /**
     * Setup loggers and perform initialization.
     */
    public static void setupLogger() {
        SimpleLogFormatter.configureGlobalLogger();
    }

    /**
     * Bootstrap.
     *
     * @param args args
     */
    public static void main(final String[] args) {
        setupLogger();

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    Launcher launcher = createFromArguments(Launcher.class, args);
                    SwingHelper.setSwingProperties(tr("launcher.appTitle", launcher.getVersion()));
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    launcher.showLauncherWindow();
                } catch (Throwable t) {
                    log.log(Level.WARNING, "Load failure", t);
                    SwingHelper.showErrorDialog(null, "Uh oh! The updater couldn't be opened because a " +
                            "problem was encountered.", "Launcher error", t);
                }
            }
        });

    }

}
