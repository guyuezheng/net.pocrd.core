package net.pocrd.util;

import net.pocrd.annotation.CacheMethod;
import net.pocrd.annotation.CacheParameter;
import net.pocrd.annotation.CacheParameter.CacheKeyType;
import net.pocrd.core.PocClassLoader;
import net.pocrd.entity.CommonConfig;
import net.pocrd.entity.CompileConfig;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generate subclass with cacheMethod,then create and cache an single instance for input class. 延迟注册，只要需要代理的时候才会生成
 *
 * @param <T>
 *
 * @author guankaiqiang
 */
public class CacheProvider implements Opcodes {
    /**
     * 缓存实现机制
     *
     * @author guankaiqiang
     */
    public enum CacheDBType {
        Redis,
        Memcache
    }

    private static       ConcurrentHashMap<Class<?>, Object> cache         = new ConcurrentHashMap<Class<?>, Object>();
    private final static String                              CACHE_SPLITER = "|";

    @SuppressWarnings("unchecked")
    public static <T> T getSingleton(Class<T> clazz) {
        T instance = (T)cache.get(clazz);
        if (instance == null) {
            synchronized (cache) {
                instance = (T)cache.get(clazz);
                if (instance == null) {
                    instance = createSingleton(clazz);
                    cache.put(clazz, instance);
                }
            }
        }
        return instance;
    }

    private static <T> T createSingleton(Class<T> clazz) {
        try {
            if (hasCacheMethod(clazz)) {
                return newCachedClassInstance(clazz);
            } else {
                return SingletonUtil.getSingleton(clazz);
            }
        } catch (Exception e) {
            throw new RuntimeException("单例创建失败", e);
        }
    }

    /**
     * @param clazz CacheProvider generates cachedClass, only if target has at least one or more method which can be accessed publicly and its return
     *              type is not void and is not final,not abstract or not static
     *
     * @author guankaiqiang
     */
    public static <T> boolean hasCacheMethod(Class<T> clazz) {
        boolean isValid = false;
        Method[] methods = clazz.getMethods();
        for (Method m : methods) {
            isValid |= checkCacheMethod(m);
        }
        return isValid;
    }

    /**
     * 检测方法是符合被代理的条件
     *
     * @param method
     */
    private static boolean checkCacheMethod(Method method) {
        CacheMethod cacheAnnotation = method.getAnnotation(CacheMethod.class);
        if (cacheAnnotation != null && cacheAnnotation.enable()) {
            Class<?> returnType = method.getReturnType();
            int mod = method.getModifiers();
            if (Modifier.isAbstract(mod)) throw new RuntimeException("Method can not be abstract,method name:" + method.getName());
            if (!Modifier.isPublic(mod)) throw new RuntimeException("Method must be public,method name:" + method.getName());
            if (Modifier.isFinal(mod)) throw new RuntimeException("Method can not be final,method name:" + method.getName());
            if (Modifier.isStatic(mod)) throw new RuntimeException("Method can not be static,method name:" + method.getName());
            if ("void".equals(returnType.getName())) {
                if (CompileConfig.isDebug)// 空方法可以跳过代理
                { throw new RuntimeException("Method return type can not be void,method name:" + method.getName()); } else { return false; }
            }
            return true;
        } else { return false; }
    }

    /**
     * cacheType TODO：更好的实现，提供使用者自定义的方式
     *
     * @return
     */
    public static ICacheManager getCacheManager() {
        String cacheType = CommonConfig.getInstance().getCacheType();
        if (CacheDBType.Redis.name().equals(cacheType)) {
            return (ICacheManager)SingletonUtil.getSingleton(CacheManager4Redis.class);
        } else if (CacheDBType.Memcache.name().equals(cacheType)) {
            return (ICacheManager)SingletonUtil.getSingleton(CacheManager4Memcache.class);
        } else {
            throw new RuntimeException("不支持的缓存实现机制：" + cacheType);
        }
    }

    private final static Type stringType        = Type.getType(String.class);
    private final static Type stringBuilderType = Type.getType(StringBuilder.class);

    private final static CacheParameter getCacheParameterAnnotation(Annotation[] annotationsOfParam) {
        if (annotationsOfParam != null && annotationsOfParam.length != 0) {
            for (Annotation annotation : annotationsOfParam) {
                if (annotation.annotationType() == CacheParameter.class) {
                    return (CacheParameter)annotation;
                }
            }
        }
        return null;
    }

    /**
     * 为何使用 cachekey而非使用classname+methoddescriptor做为cachekey： 1.这样做便于缓存管理；
     * 2.当发现使用了重复的cachekey时，能让开发人员发现已经存在了这样一个缓存实现，以便开发者去决定修改这个函数或者是复用这个函数；(这个更倾向于编译期检查warning) 生成缓存Instatnce 避免缓存数据序列化异常解决方案：
     * 1.返回值类型进行了属性修改，系统新发布时cacheDB中缓存数据未失效，如果cache命中会导致序列化异常， 通过使用cacheVersion，避免命中无效cache数据，来规避属性修改导致序列化异常； 2.约束缓存键名的使用管理； 3.instanceof做类型检测，支持多态
     *
     * @return
     *
     * @author guankaiqiang
     */
    @SuppressWarnings("unchecked")
    public static <T> T newCachedClassInstance(Class<T> clazz) {
        try {
            String className = "net/pocrd/autogen/Cache_" + clazz.getSimpleName();
            String superClassName = clazz.getName().replace('.', '/');
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            MethodVisitorWrapper mvWrapper;
            cw.visit(V1_6, ACC_PUBLIC + ACC_SUPER, className, null, superClassName, null);
            cw.visitSource("Cache_" + clazz.getSimpleName() + ".java", null);
            {
                // init
                mvWrapper = new MethodVisitorWrapper(cw, ACC_PUBLIC, "<init>", "()V", null, null);// Init函数
                mvWrapper.visitCode();
                Label l0 = new Label();
                mvWrapper.visitLabel(l0);
                mvWrapper.loadArg(0);
                mvWrapper.visitMethodInsn(INVOKESPECIAL, superClassName, "<init>", "()V");
                mvWrapper.visitInsn(RETURN);
                Label l1 = new Label();
                mvWrapper.visitLabel(l1);
                mvWrapper.visitLocalVariable("this", "L" + className + ";", null, l0, l1, 0);
                mvWrapper.visitMaxs(1, 1);
                mvWrapper.visitEnd();
            }
            Method[] methods = clazz.getMethods();
            Class<?> returnType = null;
            Class<?>[] paramTypes = null;
            CacheMethod cacheAnnotation = null;
            Annotation[][] paramsAnnotations = null;
            for (Method m : methods) {
                cacheAnnotation = m.getAnnotation(CacheMethod.class);
                if (cacheAnnotation != null && cacheAnnotation.enable()) {
                    returnType = m.getReturnType();
                    if ("void".equals(returnType.getName())) {
                        continue;
                    }
                    String keyName = CommonConfig.getInstance().getCacheVersion() + CACHE_SPLITER + cacheAnnotation.key() + CACHE_SPLITER;
                    // + returnType.getCanonicalName() + CACHE_SPLITER;// returnType不参与签名，给返回结果多态提供可能
                    int expire = cacheAnnotation.expire();
                    paramTypes = m.getParameterTypes();
                    mvWrapper = new MethodVisitorWrapper(cw, m);
                    LocalVariable cacheKeyBuilder = mvWrapper.declareLocal(String.class);
                    LocalVariable cacheManagerBuilder = mvWrapper.declareLocal(ICacheManager.class);
                    LocalVariable cacheObjectBuilder = mvWrapper.declareLocal(Object.class);
                    LocalVariable returnTypeBuilder = mvWrapper.declareLocal(returnType);
                    Label ljump0 = new Label();
                    Label ljump1 = new Label();
                    Label ljump2 = new Label();
                    mvWrapper.visitCode();
                    // 1.generator cachekey
                    {
                        mvWrapper.visitTypeInsn(NEW, "java/lang/StringBuilder");
                        mvWrapper.visitInsn(DUP);
                        mvWrapper.visitLdcInsn(keyName);
                        mvWrapper.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V");
                        paramsAnnotations = m.getParameterAnnotations();
                        if (paramsAnnotations != null && paramsAnnotations.length != 0) {
                            if (paramTypes.length != paramsAnnotations.length) {
                                throw new RuntimeException("存在尚未标记CacheParameter的入参，" + m.getName());
                            }
                            int indexOfParam = 0;
                            for (Annotation[] annotations : paramsAnnotations) {
                                CacheParameter paramAnnotation = getCacheParameterAnnotation(annotations);
                                if (paramAnnotation.type() == CacheKeyType.Normal) {
                                    Class<?> paramType = paramTypes[indexOfParam];
                                    mvWrapper.loadArg(indexOfParam + 1);
                                    if (paramType.isArray()) {
                                        paramType = CacheKeyUtil.getCorrectType(paramType);// TODO
                                        mvWrapper.visitMethodInsn(INVOKESTATIC, "net/pocrd/util/CacheProvider$CacheKeyUtil", "toString",
                                                                  Type.getMethodDescriptor(stringType, Type.getType(paramType)));
                                        mvWrapper.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                                                  "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                                    } else {
                                        if (paramType.isPrimitive()) {
                                            if (int.class.equals(paramType) || short.class.equals(paramType) || byte.class.equals(paramType)) {
                                                paramType = int.class;
                                            }
                                        } else {
                                            paramType = Object.class;
                                        }
                                        mvWrapper.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                                                  Type.getMethodDescriptor(stringBuilderType, Type.getType(paramType)));
                                    }
                                    mvWrapper.visitLdcInsn(CACHE_SPLITER);
                                    mvWrapper.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                                              "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                                } else {
                                    // TODO:Support autopaging/filter
                                    throw new RuntimeException("不识别的CacheKeyType:" + paramAnnotation.type());
                                }
                                indexOfParam++;
                            }
                        }
                        mvWrapper.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
                        mvWrapper.setLocal(cacheKeyBuilder);
                    }
                    if (CompileConfig.isDebug) {
                        mvWrapper.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                        mvWrapper.loadLocal(cacheKeyBuilder);
                        mvWrapper.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V");
                    }
                    {
                        // 2.ICacheManager cacheManager = CacheProvider.getCacheManager();
                        mvWrapper.visitMethodInsn(INVOKESTATIC, "net/pocrd/util/CacheProvider", "getCacheManager",
                                                  "()Lnet/pocrd/util/ICacheManager;");
                        mvWrapper.setLocal(cacheManagerBuilder);
                    }
                    {
                        // 3.Object obj = cacheManager.get(cachekey);
                        mvWrapper.loadLocal(cacheManagerBuilder);
                        mvWrapper.loadLocal(cacheKeyBuilder);
                        mvWrapper.visitMethodInsn(INVOKEINTERFACE, "net/pocrd/util/ICacheManager", "get", "(Ljava/lang/String;)Ljava/lang/Object;");
                        mvWrapper.setLocal(cacheObjectBuilder);
                    }
                    {
                        // 4.if(obj==null)
                        mvWrapper.loadLocal(cacheObjectBuilder);
                        mvWrapper.visitJumpInsn(IFNONNULL, ljump0);
                    }
                    {
                        // 5.DemoEntity demo=super.getDemoEntity();
                        for (int i = 0; i <= m.getParameterTypes().length; i++) {
                            mvWrapper.loadArg(i);// start from this
                        }
                        mvWrapper.visitMethodInsn(INVOKESPECIAL, superClassName, m.getName(), Type.getMethodDescriptor(m));
                        mvWrapper.setLocal(returnTypeBuilder);
                    }
                    {
                        // 6.if(demo!=null)
                        if (!returnType.isPrimitive()) {
                            mvWrapper.loadLocal(returnTypeBuilder);
                            mvWrapper.visitJumpInsn(IFNULL, ljump1);
                        }
                    }
                    {
                        // 7.cacheManager.set(cachekey,demoEntity,expire);
                        mvWrapper.loadLocal(cacheManagerBuilder);
                        mvWrapper.loadLocal(cacheKeyBuilder);
                        mvWrapper.loadLocal(returnTypeBuilder);
                        if (returnType.isPrimitive()) {
                            mvWrapper.doInbox(returnType);// inbox
                        }
                        mvWrapper.visitIntInsn(BIPUSH, expire);
                        mvWrapper.visitMethodInsn(INVOKEINTERFACE, "net/pocrd/util/ICacheManager", "set", "(Ljava/lang/String;Ljava/lang/Object;I)Z");
                        mvWrapper.visitInsn(POP);
                        mvWrapper.loadLocal(returnTypeBuilder);
                        mvWrapper.doReturn(returnType);
                    }
                    {
                        // 8.return null;
                        if (!returnType.isPrimitive()) {
                            mvWrapper.visitLabel(ljump1);
                            mvWrapper.visitInsn(ACONST_NULL);
                            mvWrapper.doReturn(returnType);
                        }
                    }
                    {
                        // if (obj instanceof Integer)
                        // return ((Integer) obj).intValue();
                        {
                            mvWrapper.visitLabel(ljump0);
                            mvWrapper.loadLocal(cacheObjectBuilder);
                            mvWrapper.doInstanceof(returnType);
                            mvWrapper.visitJumpInsn(IFEQ, ljump2);
                            mvWrapper.loadLocal(cacheObjectBuilder);
                            mvWrapper.doCast(returnType);
                            mvWrapper.doReturn(returnType);
                        }
                        {
                            // else throw new RuntimeException(...);
                            mvWrapper.visitLabel(ljump2);
                            mvWrapper.visitTypeInsn(NEW, "java/lang/RuntimeException");
                            mvWrapper.visitInsn(DUP);
                            mvWrapper.visitTypeInsn(NEW, "java/lang/StringBuilder");
                            mvWrapper.visitInsn(DUP);
                            mvWrapper.visitLdcInsn("Cache object conflict,key:");
                            mvWrapper.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V");
                            mvWrapper.loadLocal(cacheKeyBuilder);
                            mvWrapper.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                                      "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                            mvWrapper.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
                            mvWrapper.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V");
                            mvWrapper.visitInsn(ATHROW);
                        }
                    }
                    mvWrapper.visitMaxs(0, 0);
                    mvWrapper.visitEnd();
                }
            }
            cw.visitEnd();
            if (CompileConfig.isDebug) {
                outPutClassFile("Cache_" + clazz.getSimpleName(), cw.toByteArray());
            }
            T e = (T)new PocClassLoader(CacheProvider.class.getClassLoader()).defineClass(className.replace('/', '.'),
                                                                                          cw.toByteArray()).newInstance();
            return e;
        } catch (Exception e) {
            throw new RuntimeException("generator failed. " + clazz.getName(), e);
        }
    }

    private static void outPutClassFile(String fileName, byte[] byteArray) {
        FileOutputStream fos = null;
        try {
            File folder = new File(CommonConfig.getInstance().getAutogenPath() + File.separator + "CachedClass" + File.separator);
            if (!folder.exists()) {
                folder.mkdirs();
            }
            fos = new FileOutputStream(
                    CommonConfig.getInstance().getAutogenPath() + File.separator + "CachedClass" + File.separator + fileName + ".class");
            fos.write(byteArray);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {

                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * @author guankaiqiang
     */
    public static class CacheKeyUtil {
        /**
         * @param array
         *
         * @return
         */
        public static String toString(int[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * 避免Integer[]做unbox
         *
         * @param array
         *
         * @return
         */
        public static String toString(Integer[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Integer i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(boolean[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (boolean i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(Boolean[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Boolean i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(short[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (short i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(Short[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Short i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(byte[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (byte i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(Byte[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Byte i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(float[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (float i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(Float[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Float i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(double[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (double i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(Double[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Double i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(long[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (long i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(Long[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Long i : array) {
                sb.append("[" + i + "]");
            }
            return sb.toString();
        }

        /**
         * @param array
         *
         * @return
         */
        public static String toString(String[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (String i : array) {
                if (i != null) { sb.append("[" + i + "]"); } else sb.append("[null]");
            }
            return sb.toString();
        }

        /**
         * array.toString()
         *
         * @return
         */
        public static String toString(Object[] array) {
            if (array == null || array.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Object i : array) {
                if (i != null) { sb.append("[" + i + "]"); } else sb.append("[null]");
            }
            return sb.toString();
        }

        private final static Class<?>          intArrayClass     = int[].class;
        private final static Class<?>          IntegerArrayClass = Integer[].class;
        private final static Class<?>          shortArrayClass   = short[].class;
        private final static Class<?>          ShortArrayClass   = Short[].class;
        private final static Class<?>          byteArrayClass    = byte[].class;
        private final static Class<?>          ByteArrayClass    = Byte[].class;
        private final static Class<?>          floatArrayClass   = float[].class;
        private final static Class<?>          FloatArrayClass   = Float[].class;
        private final static Class<?>          longArrayClass    = long[].class;
        private final static Class<?>          LongArrayClass    = Long[].class;
        private final static Class<?>          doubleArrayClass  = double[].class;
        private final static Class<?>          DoubleArrayClass  = Double[].class;
        private final static Class<?>          booleanArrayClass = boolean[].class;
        private final static Class<?>          BooleanArrayClass = Boolean[].class;
        private final static Class<?>          StringArrayClass  = String[].class;
        private final static Class<?>          ObjectArrayClass  = Object[].class;
        private static       HashSet<Class<?>> classSet          = new HashSet<Class<?>>();
        static {
            classSet.add(intArrayClass);
            classSet.add(IntegerArrayClass);
            classSet.add(shortArrayClass);
            classSet.add(ShortArrayClass);
            classSet.add(byteArrayClass);
            classSet.add(ByteArrayClass);
            classSet.add(floatArrayClass);
            classSet.add(FloatArrayClass);
            classSet.add(longArrayClass);
            classSet.add(LongArrayClass);
            classSet.add(doubleArrayClass);
            classSet.add(DoubleArrayClass);
            classSet.add(booleanArrayClass);
            classSet.add(BooleanArrayClass);
            classSet.add(StringArrayClass);
            classSet.add(ObjectArrayClass);
        }

        /**
         * 是否需要类型转换
         *
         * @param clazz
         *
         * @return
         */
        static Class<?> getCorrectType(Class<?> clazz) {
            return classSet.contains(clazz) ? clazz : Object[].class;
        }
    }
}