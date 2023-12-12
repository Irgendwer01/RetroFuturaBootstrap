package net.minecraft.launchwrapper;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class LaunchClassLoader extends URLClassLoader {

    /** Internal IO buffer size */
    public static final int BUFFER_SIZE = 1 << 12;
    /** A list keeping track of addURL calls */
    private List<URL> sources;
    /** A reference to the classloader that loaded this class */
    private ClassLoader parent = getClass().getClassLoader();
    /** RFB: Reference to the platform class loader that can load JRE/JDK classes */
    private static final ClassLoader platformLoader = ClassLoader.getPlatformClassLoader();

    /** An ArrayList of all class transformers used, mutable, often modified via reflection */
    private List<IClassTransformer> transformers = new ArrayList<>(2);
    /** A ConcurrentHashMap cache of all classes loaded via this loader */
    private Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    /**
     * A HashSet (probably wrong, not thread safe) cache of class names that previously caused exceptions when
     * attempting to load them
     */
    private Set<String> invalidClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(1000));

    /**
     * A HashSet of all class prefixes (e.g. "org.lwjgl.") to redirect to the parent classloader, often modified via
     * reflection
     */
    private Set<String> classLoaderExceptions = new HashSet<>();
    /**
     * A HashSet of all class prefixes (e.g. "org.objectweb.asm.") to NOT run class transformers on, often modified via
     * reflection
     */
    private Set<String> transformerExceptions = new HashSet<>();
    /**
     * An unused cache of package manifests, the field with a non-null CHM value needs to stay here due to reflective
     * usage
     */
    private Map<Package, Manifest> packageManifests = new ConcurrentHashMap<>();
    /** A ConcurrentHashMap cache of class bytes loaded via this classloader */
    private Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1000);
    /** A concurrent cache of class bytes that previously caused exceptions when attempting to load them */
    private Set<String> negativeResourceCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** A transformer used to remap class names */
    private IClassNameTransformer renameTransformer;

    /** A dummy empty manifest field, used reflectively by some mods */
    private static final Manifest EMPTY = new Manifest();

    /** A utility IO buffer */
    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<>();

    /**
     * A list of filenames forbidden by Windows, <a
     * href="https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file#naming-conventions">MSDN entry</a>
     */
    private static final String[] RESERVED_NAMES = {
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
        "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    /** A system property determining if additional debug output should be generated when loading classes */
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
    /**
     * A system property determining if additional debug output should be generated when loading classes on top of
     * DEBUG
     */
    private static final boolean DEBUG_FINER =
            DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingFiner", "false"));
    /** A system property determining if post-transform class bytes should be dumped on top of DEBUG */
    private static final boolean DEBUG_SAVE =
            DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));
    /** Folder where the class dumps are saved */
    private static File tempFolder = null;

    /**
     * <ol>
     *     <li>Constructs a parent-less super URLClassLoader</li>
     *     <li>Saves the given classpath to this.sources</li>
     *     <li>Adds class loader exclusions for:<ul>
     *         <li>java.</li>
     *         <li>sun.</li>
     *         <li>org.lwjgl.</li>
     *         <li>org.apache.logging.</li>
     *         <li>net.minecraft.launchwrapper.</li>
     *     </ul></li>
     *     <li>Adds transformer exclusions for:<ul>
     *         <li>javax.</li>
     *         <li>argo.</li>
     *         <li>org.objectweb.asm.</li>
     *         <li>com.google.common.</li>
     *         <li>org.bouncycastle.</li>
     *         <li>net.minecraft.launchwrapper.injector.</li>
     *     </ul></li>
     *     <li>If DEBUG_SAVE, finds a suitable directory to dump classes to, mkdirs it and saves it to tempFolder</li>
     * </ol>
     *
     * @param sources The initial classpath
     */
    public LaunchClassLoader(URL[] sources) {
        super("RFB", sources, ClassLoader.getPlatformClassLoader());
        LogWrapper.configureLogging();
        this.sources = new ArrayList<>(List.of(sources));
        classLoaderExceptions.addAll(List.of(
                "java.",
                "org.lwjgl.",
                "org.apache.logging.",
                "net.minecraft.launchwrapper.",
                "com.gtnewhorizons.retrofuturabootstrap."));
        transformerExceptions.addAll(List.of(
                "javax.",
                "argo.",
                "org.objectweb.asm.",
                "com.google.common.",
                "org.bouncycastle.",
                "net.minecraft.launchwrapper.injector.",
                "com.gtnewhorizons.retrofuturabootstrap."));
        // TODO: find dump save folder
    }

    /**
     * Reflectively constructs the given transformer, and adds it to the transformer list. If the transformer is a
     * IClassNameTransformer and one wasn't already registered, sets renameTransformer to it. Catches any exceptions,
     * and ignores them while logging a message.
     *
     * @param transformerClassName A class name (like a.b.Cde) of the transformer.
     */
    public void registerTransformer(String transformerClassName) {
        try {
            Class<?> xformerClass = Class.forName(transformerClassName, true, this);
            if (!IClassTransformer.class.isAssignableFrom(xformerClass)) {
                LogWrapper.severe(
                        "Tried to register a transformer {} which does not implement IClassTransformer",
                        transformerClassName);
                return;
            }
            IClassTransformer xformer =
                    (IClassTransformer) xformerClass.getConstructor().newInstance();
            if (renameTransformer == null && xformer instanceof IClassNameTransformer nameXformer) {
                renameTransformer = nameXformer;
            }
            transformers.add(xformer);
        } catch (Throwable e) {
            LogWrapper.logger.warn("Could not register a transformer {}", transformerClassName, e);
        }
    }

    /**
     * <ol>
     *     <li>If invalidClasses contains name, throw ClassNotFoundException</li>
     *     <li>If name starts with any class loader exception, forward to parent.loadClass</li>
     *     <li>If in cachedClasses, return the cached class</li>
     *     <li>If name starts with any transformer exception, super.findClass, put into cachedClasses and return. Catch and cache ClassNotFoundException.</li>
     *     <li>transformName, and check the cache again - if present, return from cache</li>
     *     <li>untransformName, find the last dot and use that to determine the package name and file path of the .class file</li>
     *     <li>Open a URLConnection using findCodeSourceConnectionFor on the determined filename</li>
     *     <li>Check package sealing for the given URLConnection, unless the untransformed name starts with "net.minecraft.", giving a severe warning if the package is already sealed. Otherwise, create and register a new Package.</li>
     *     <li>runTransformers on getClassBytes</li>
     *     <li>Save the debug class if enabled</li>
     *     <li>defineClass with the appropriate CodeSigners</li>
     *     <li>Cache and return the class</li>
     *     <li>Throw a ClassNotFoundException if any exception happens during the transforming process</li>
     * </ol>
     */
    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) {
            throw new ClassNotFoundException(name + " in invalid class cache");
        }
        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
            }
        }
        {
            final Class<?> cached = cachedClasses.get(name);
            if (cached != null) {
                return cached;
            }
        }
        boolean runTransformers = true;
        for (final String exception : transformerExceptions) {
            if (name.startsWith(exception)) {
                runTransformers = false;
                break;
            }
        }
        final String transformedName = transformName(name);
        {
            Class<?> transformedClass = cachedClasses.get(transformedName);
            if (transformedClass != null) {
                return transformedClass;
            }
        }
        final String untransformedName = untransformName(name);
        final int lastDot = untransformedName.lastIndexOf('.');
        final String packageName = (lastDot == -1) ? "" : untransformedName.substring(0, lastDot);
        final String classPath = untransformedName.replace('.', '/') + ".class";
        final URLConnection connection = findCodeSourceConnectionFor(classPath);
        final Package pkg;
        final CodeSource codeSource;
        if (!packageName.isEmpty()) {
            if (connection instanceof JarURLConnection jarConnection) {
                final URL codeSourceUrl = jarConnection.getJarFileURL();
                Manifest manifest = null;
                try {
                    manifest = jarConnection.getManifest();
                } catch (IOException e) {
                    // no-op
                }
                pkg = getAndVerifyPackage(packageName, manifest, codeSourceUrl);
                try {
                    codeSource = new CodeSource(codeSourceUrl, jarConnection.getCertificates());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                pkg = getAndVerifyPackage(packageName, null, null);
                codeSource = connection == null ? null : new CodeSource(connection.getURL(), (Certificate[]) null);
            }
        } else {
            pkg = null;
            codeSource = null;
        }
        byte[] classBytes = null;
        try {
            classBytes = getClassBytes(untransformedName);
        } catch (IOException e) {
            /* no-op */
        }
        if (runTransformers) {
            try {
                classBytes = runTransformers(untransformedName, transformedName, classBytes);
            } catch (Throwable t) {
                var err = new ClassNotFoundException("Exception caught while transforming class " + transformedName, t);
                LogWrapper.logger.debug("Tranformer error", err);
                throw err;
            }
        }
        if (classBytes == null) {
            invalidClasses.add(name);
            throw new ClassNotFoundException(
                    "Class bytes are null for %s (%s, %s)".formatted(transformedName, name, untransformedName));
        }
        Class<?> result = defineClass(transformedName, classBytes, 0, classBytes.length, codeSource);
        cachedClasses.put(transformedName, result);
        return result;
    }

    // based off OpenJDK's own URLClassLoader
    private Package getAndVerifyPackage(final String packageName, final Manifest manifest, final URL codeSourceURL) {
        final Package pkg = getDefinedPackage(packageName);
        if (pkg != null) {
            if (pkg.isSealed() && !pkg.isSealed(codeSourceURL)) {
                throw new SecurityException("Sealing violation in package " + packageName);
            } else if (manifest != null && isSealed(packageName, manifest)) {
                throw new SecurityException("Sealing violation in already loaded package " + packageName);
            }
        } else {
            return definePackage(packageName, EMPTY, codeSourceURL);
        }
        return pkg;
    }

    /**
     * <ol>
     *     <li>If tempFolder is null, return immediately</li>
     *     <li>Generate an output path for a class org.my.Klass as tempFolder/org/my/Klass.class</li>
     *     <li>Make the directory if it doesn't already exist, delete the output file if it already exists</li>
     *     <li>Write data to the file, ignoring exceptions</li>
     * </ol>
     */
    private void saveTransformedClass(final byte[] data, final String transformedName) {
        // TODO
    }

    /** A null-safe version of renameTransformer.unmapClassName(name) */
    private String untransformName(final String name) {
        if (renameTransformer == null || name == null) {
            return name;
        }
        final String newName = renameTransformer.unmapClassName(name);
        return newName == null ? name : newName;
    }

    /** A null-safe version of renameTransformer.remapClassName(name) */
    private String transformName(final String name) {
        if (renameTransformer == null || name == null) {
            return name;
        }
        final String newName = renameTransformer.remapClassName(name);
        return newName == null ? name : newName;
    }

    /**
     * Checks the manifest path SEALED attribute, then checks the main attributes for the sealed property. Returns if
     * present and equal to "true" ignoring case.
     */
    private boolean isSealed(final String packageName, final Manifest manifest) {
        if (manifest == null) {
            return false;
        }
        final String path = packageName.replace('.', '/') + '/';
        final Attributes attributes = manifest.getAttributes(path);
        if (attributes != null) {
            final String value = attributes.getValue(Attributes.Name.SEALED);
            if (value != null) {
                return value.equalsIgnoreCase("true");
            }
        }
        final Attributes mainAttributes = manifest.getMainAttributes();
        if (mainAttributes != null) {
            final String value = mainAttributes.getValue(Attributes.Name.SEALED);
            if (value != null) {
                return value.equalsIgnoreCase("true");
            }
        }
        return false;
    }

    /**
     * Calls findResource, and if it's not null opens a connection to it, otherwise returns null.
     */
    private URLConnection findCodeSourceConnectionFor(final String name) {
        try {
            final URL url = findResource(name);
            return url.openConnection();
        } catch (Exception e) {
            LogWrapper.logger.debug("Couldn't findCodeSourceConnectionFor {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * <ol>
     *     <li>For each transformer on the transformer list, transform basicClass</li>
     *     <li>Return the updated basicClass</li>
     * </ol>
     */
    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        for (IClassTransformer xformer : transformers) {
            try {
                final byte[] newKlass = xformer.transform(name, transformedName, basicClass);
                // TODO: diff
                basicClass = newKlass;
            } catch (UnsupportedOperationException e) {
                if (e.getMessage().contains("requires ASM")) {
                    LogWrapper.logger.warn(
                            "ASM transformer {} encountered a newer classfile ({} -> {}) than supported: {}",
                            xformer.getClass().getName(),
                            name,
                            transformedName,
                            e.getMessage());
                    continue;
                }
                throw e;
            }
        }
        return basicClass;
    }

    /**
     * Adds the given url to the classpath (via super) and the sources field.
     */
    @Override
    public void addURL(final URL url) {
        super.addURL(url);
        sources.add(url);
    }

    /** Returns the saved classpath list */
    public List<URL> getSources() {
        return sources;
    }

    /**
     * Tries to fully read the input stream into a byte array<strike>, using getOrCreateBuffer() as the IO
     * buffer</strike>. Returns an empty array and logs a warning if any exception happens.
     */
    private byte[] readFully(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (Exception e) {
            LogWrapper.logger.warn("Could not read InputStream {}", stream.toString(), e);
            return new byte[0];
        }
    }

    /**
     * A TLS helper for the loadBuffer field, creates a byte[BUFFER_SIZE] array if empty.
     */
    @SuppressWarnings("unused") // keep for reflection compat if needed
    private byte[] getOrCreateBuffer() {
        byte[] buf = loadBuffer.get();
        if (buf == null) {
            buf = new byte[BUFFER_SIZE];
            loadBuffer.set(buf);
        }
        return buf;
    }

    /** Returns an unmodifiable view of the transformers list */
    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    /** RFB: Returns a modifiable view of the transformers list */
    public List<IClassTransformer> getMutableTransformers() {
        return transformers;
    }

    /** Adds a new entry to the end of the class loader exclusions list */
    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
    }

    /** Adds a new entry to the end of the transformer exclusions list */
    public void addTransformerExclusion(String toExclude) {
        transformerExceptions.add(toExclude);
    }

    /**
     * <ol>
     *     <li>If negativeResourceCache contains name, return null</li>
     *     <li>If resourceCache contains name, return the value</li>
     *     <li>If there is no '.' in the name and the name is one of the reserved names (ignore case), cache and return getClassBytes("_name")</li>
     *     <li>Find and load the resource with the path of name with . replaced with / and .class appended</li>
     *     <li>Cache and return the contents</li>
     *     <li>Silently close the stream regardless of any exceptions (but don't suppress exceptions)</li>
     * </ol>
     */
    public byte[] getClassBytes(String name) throws IOException {
        if (negativeResourceCache.contains(name)) {
            return null;
        }
        final byte[] cached = resourceCache.get(name);
        if (cached != null) {
            return cached.clone();
        }
        if (!name.contains(".") && name.length() >= 3 && name.length() <= 4) {
            for (final String reserved : RESERVED_NAMES) {
                if (reserved.equalsIgnoreCase(name)) {
                    final byte[] underscored = getClassBytes("_" + name);
                    if (underscored != null) {
                        resourceCache.put(name, underscored);
                    } else {
                        negativeResourceCache.add(name);
                    }
                    return underscored;
                }
            }
        }
        final String classPath = name.replace('.', '/') + ".class";
        URLConnection conn = findCodeSourceConnectionFor(classPath);
        if (conn == null) {
            // Try JRE classes
            final URL platformUrl = platformLoader.getResource(classPath);
            if (platformUrl != null) {
                conn = platformUrl.openConnection();
            }
        }
        if (conn == null) {
            negativeResourceCache.add(name);
            return null;
        }
        final InputStream is = conn.getInputStream();
        final byte[] contents = readFully(is);
        closeSilently(is);
        if (contents == null) {
            negativeResourceCache.add(name);
            return null;
        }
        resourceCache.put(name, contents);
        return contents.clone();
    }

    /** Null-safe, exception-safe close function that silently ignores any errors */
    private static void closeSilently(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable e) {
            // no-op
        }
    }

    /** Removes all of entriesToClear from negativeResourceCache */
    public void clearNegativeEntries(Set<String> entriesToClear) {
        negativeResourceCache.removeAll(entriesToClear);
    }
}
