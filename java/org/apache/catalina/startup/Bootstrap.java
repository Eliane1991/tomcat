/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    private static final Object daemonLock = new Object();
    private static volatile Bootstrap daemon = null;

    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;

    private static final Pattern PATH_PATTERN = Pattern.compile("(\"[^\"]*\")|(([^,])*)");

    static {
        // Will always be non-null
        String userDir = System.getProperty("user.dir");

        // Home first
        String home = System.getProperty(Constants.CATALINA_HOME_PROP);
        File homeFile = null;

        if (home != null) {
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory
            // in a normal Tomcat install
            File bootstrapJar = new File(userDir, "bootstrap.jar");

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        if (homeFile == null) {
            // Second fall-back. Use current directory
            File f = new File(userDir);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        catalinaHomeFile = homeFile;
        System.setProperty(
                Constants.CATALINA_HOME_PROP, catalinaHomeFile.getPath());

        // Then base
        String base = System.getProperty(Constants.CATALINA_BASE_PROP);
        if (base == null) {
            catalinaBaseFile = catalinaHomeFile;
        } else {
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        System.setProperty(
                Constants.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * Daemon reference.
     */
    private Object catalinaDaemon = null;

    ClassLoader commonLoader = null;
    /**
     * serverLoader
     */
    ClassLoader catalinaLoader = null;
    ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods


    private void initClassLoaders() {
        try {
            /**
             * commonLoader的父类加载器最终由ClassLoader的构造方法赋值，是由getSystemClassLoader()得到的AppClassLoader
             */
            commonLoader = createClassLoader("common", null);
            if (commonLoader == null) {
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader = this.getClass().getClassLoader();
            }
            /**
             * CatalinaProperties.getProperty(name + ".loader") 默认情况下返回的是空值
             * 所以commonLoader,catalinaLoader,sharedLoader都是同一个对象.
             * 如果需要开启,需要下catalina.config 或者 catalina.properties配置文件中配置项
             * server.loader= 代码加载路径
             * shared.loader= 代码加载路径
             */
            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }


    private ClassLoader createClassLoader(String name, ClassLoader parent)
            throws Exception {

        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals("")))
            return parent;

        value = replace(value);

        List<Repository> repositories = new ArrayList<>();

        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // Local repository
            if (repository.endsWith("*.jar")) {
                repository = repository.substring
                        (0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
        }

        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Constants.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Constants.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * Initialize daemon.
     *
     * @throws Exception Fatal initialization error
     */
    public void init() throws Exception {
        /**
         *  初始化tomcat的内部类加载器
         *  tomcat通过这个类加载器打破了传统类加载的双亲委派机制
         *  这是基于以下原因:
         *  1.webApp自身的class类需要和tomcat web容器的类,基于安全考虑,需要做隔离.
         *    相同类,webApp可能会处于业务的原因做修改.tomcat的class先加载了的话,webApp的类就不会生效了.
         *  2.每个WebAPP中都有自己的jar包,webApp之间的类库需要做隔离,因为这些jar包会出现相同依赖class但是不同版本,
         *    如果不打破双亲委派,那么整个tomcat jvm中就只能存在一份class.
         *  3.webApp中jsp文件都会被编译成class文件加载,如果jsp文件修改了,但是因为类加载机制在,修改后的class文件就不会被加载了.
         *   因为jvm内存中已经有了以一个类在内存了.所以需要这么一个jsp文件对应一个唯一的类加载器,
         *   如果文件修改了,只需要把这个类加载器直接卸载掉就好了.重新创建jsp classload,重新加载jsp文件
         *
         *  commonLoader是catalinaLoader,sharedLoader这2个类加载器的父类加载器
         *      tomcat最基本的类加载器，加载路径中的class可以被Tomcat容器本身以及各个Webapp访问,
         *      以System Class Loader 为父类加载器, 是位于Tomcat 应用服务器顶层的公用类加载器,默认是加载$CATALINE_HOME/lib 下的jar 包
         *  catalinaLoader是Tomcat容器私有的类加载器，加载路径中的class对于Webapp不可见
         *      以Common Class Loader 为父加载器.用于加载 Tomcat 应用服务器本身的.
         *  sharedLoader是各个Webapp共享的类加载器，加载路径中的class对于所有Webapp可见，但是对于Tomcat容器不可见
         *      以Common 为父加载器,是所有web应用的父加载器
         *
         *  默认情况下catalina.porperties中的"server.loader"、"shared.loader"配置都为空，所以catalinaLoader、sharedLoader、commonLoader三者是同一个对象
         *
         *
         *      因为代码中 src文件夹中的文件java以及webContent中的JSP都会在tomcat启动时，被编译成class文件放在 WEB-INF/class 中。
         *      而外部引用的jar包，则相当于放在 WEB-INF/lib 中。
         *      因此肯定是 java文件或者JSP文件编译出的class优先加载。
         *
         */
        initClassLoaders();
        /**
         * 这里也是为了打破双亲委派,设置线程的上下文类加载器为catalinaLoader后,如果出现父类需要加载子类的情况,
         * 例如jdbc jdni应用,那么就会使用catalinaLoader 去加载子类
         *
         */
        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        if (log.isDebugEnabled())
            log.debug("Loading startup class");
        /**
         * 调用默认的构造方法,实例化catalina
         */
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        Object startupInstance = startupClass.getConstructor().newInstance();

        // Set the shared extensions class loader
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");
        String methodName = "setParentClassLoader";

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");

        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;

        /**
         * todo
         * 通过反射,调用Catalina的setParentClassLoader(classload)方法,设置catalina的parentClassLoader为sharedLoader
         * 也相当于设置了Engine 容器的的类加载器
         *
         */
        Method method = startupInstance.getClass().getMethod(methodName, paramTypes);
        method.invoke(startupInstance, paramValues);

        catalinaDaemon = startupInstance;
    }


    /**
     * Load daemon.
     */
    private void load(String[] arguments) throws Exception {

        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
                catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled()) {
            log.debug("Calling startup class " + method);
        }
        method.invoke(catalinaDaemon, param);
    }


    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     *
     * @param arguments Initialization arguments
     * @throws Exception Fatal initialization error
     */
    public void init(String[] arguments) throws Exception {

        init();
        load(arguments);
    }


    /**
     * Start the Catalina daemon.
     *
     * @throws Exception Fatal start error
     */
    public void start() throws Exception {
        if (catalinaDaemon == null) {
            init();
        }

        Method method = catalinaDaemon.getClass().getMethod("start", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the Catalina Daemon.
     *
     * @throws Exception Fatal stop error
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the standalone server.
     *
     * @throws Exception Fatal stop error
     */
    public void stopServer() throws Exception {

        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the standalone server.
     *
     * @param arguments Command line arguments
     * @throws Exception Fatal stop error
     */
    public void stopServer(String[] arguments) throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }


    /**
     * Set flag.
     *
     * @param await <code>true</code> if the daemon should block
     * @throws Exception Reflection error
     */
    public void setAwait(boolean await)
            throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
                catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }

    public boolean getAwait() throws Exception {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
                catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {

        synchronized (daemonLock) {
            if (daemon == null) {
                // Don't set daemon until init() has completed
                /**
                 * 实例化bootstrap对象,Bootstrap 的static静态代码块中,初始化了home path路径和base path路径
                 * catalina.home(tomcat运行程序路径)指向公用信息的位置，就是bin和lib的父目录。
                 * catalina.base(工作路径)指向每个Tomcat目录私有信息的位置，就是conf、logs、temp、webapps和work的父目录。
                 * 仅运行一个Tomcat实例时，这两个属性指向的位置是相同的。
                 */
                Bootstrap bootstrap = new Bootstrap();
                try {
                    bootstrap.init();
                } catch (Throwable t) {
                    handleThrowable(t);
                    t.printStackTrace();
                    return;
                }
                daemon = bootstrap;
            } else {
                // When running as a service the call to stop will be on a new
                // thread so make sure the correct class loader is used to
                // prevent a range of class not found exceptions.
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
            }
        }

        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                /**
                 * 主要是调用catalina类的load方法,完成tomcat各个组件的初始化
                 * server.init() -->service.init()  完成了server.xml的解析,构建了整体的初始化环境,然后责任链交给了service.init()
                 * service.init() --> engine.init() --> executor.init --> mapperListener.init() -->connector.init()
                 * 涉及到的组件有
                 * service->engine->executor->mapperListener->connector->Adapter->endpoint
                 */
                daemon.load(args);
                /**
                 * 主要是调用catalina类的start,启动tomcat的各个组件
                 */
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                daemon.setAwait(true);
                daemon.load(args);
                daemon.start();
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * Obtain the name of configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     *
     * @return the catalina home
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * Obtain the name of the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHome()} will be used.
     *
     * @return the catalina base
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * Obtain the configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     *
     * @return the catalina home as a file
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * Obtain the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHomeFile()} will be used.
     *
     * @return the catalina base as a file
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start
    static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof StackOverflowError) {
            // Swallow silently - it should be recoverable
            return;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }

    // Copied from ExceptionUtils so that there is no dependency on utils
    static Throwable unwrapInvocationTargetException(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    // Protected for unit testing
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();
            if (path.length() == 0) {
                continue;
            }

            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);

            if (first == '"' && last == '"' && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
                if (path.length() == 0) {
                    continue;
                }
            } else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                throw new IllegalArgumentException(
                        "The double quote [\"] character can only be used to quote paths. It must " +
                                "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }

            result.add(path);
        }
        return result.toArray(new String[0]);
    }
}
