package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.Kubernetes.KubernetesManager;
import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.utils.DeferredLoggerFactory;
import com.ctrip.framework.apollo.core.utils.StringUtils;
import com.ctrip.framework.apollo.enums.ConfigSourceType;
import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.ctrip.framework.apollo.util.ExceptionUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author dyx1234
 */
public class K8sConfigMapConfigRepository extends AbstractConfigRepository
        implements RepositoryChangeListener {
    private static final Logger logger = DeferredLoggerFactory.getLogger(K8sConfigMapConfigRepository.class);
    private final String namespace;
    private String configMapName;
    private String configMapKey;
    private String configMapNamespace;
    private final ConfigUtil configUtil;
    private final KubernetesManager kubernetesManager;
    private volatile Properties configMapProperties;
    // 上游数据源
    private volatile ConfigRepository upstream;
    private volatile ConfigSourceType sourceType = ConfigSourceType.CONFIGMAP;

    /**
     * configmapNamespace 用户配的，不配用默认default
     * configmapName appid
     * configmap-key cluster+namespace
     * configmap-value 配置文件信息的json串
     */

    // TODO Properties和appConfig格式的兼容
    // TODO 初次时configMap的创建
    // TODO configUtil和k8sManager单测已完成

    /**
     * Constructor
     *
     * @param namespace the namespace
     */
    public K8sConfigMapConfigRepository(String namespace) {
        this(namespace, null);
    }

    public K8sConfigMapConfigRepository(String namespace, ConfigRepository upstream) {
        this.namespace = namespace;
        configUtil = ApolloInjector.getInstance(ConfigUtil.class);
        // 单例模式，客户端只会初始化一个
        kubernetesManager = ApolloInjector.getInstance(KubernetesManager.class);
        // 读取，默认为default
        configMapNamespace = configUtil.getConfigMapNamespace();

        this.setConfigMapKey(configUtil.getCluster(), namespace);
        this.setConfigMapName(configUtil.getAppId(), false);
        this.setUpstreamRepository(upstream);
    }

    void setConfigMapKey(String cluster, String namespace){
        // TODO 兜底key怎么设计不会冲突(cluster初始化时已经设置了层级)
        // cluster 就是填写>idc>default,所以不需要额外层级设置了
        if (StringUtils.isBlank(cluster)){
            configMapKey = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR).join("default", namespace);
            return;
        }
        configMapKey = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR).join(cluster, namespace);
    }

    void setConfigMapName(String appId, boolean syncImmediately){
        configMapName = appId;
        this.checkConfigMapName(configMapName);
        if (syncImmediately) {
            this.sync();
        }
    }

    private void checkConfigMapName(String configMapName) {
        if (StringUtils.isBlank(configMapName)) {
            throw new ApolloConfigException("ConfigMap name cannot be null");
        }
        // 判断configMap是否存在，若存在直接返回，若不存在尝试创建
        if(kubernetesManager.checkConfigMapExist(configMapNamespace, configMapName)){
            return;
        }
        // TODO 初步理解这里只生成就可以，后续update事件再写入新值

        Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "createK8sConfigMap");
        transaction.addData("configMapName", configMapName);
        try {
            kubernetesManager.createConfigMap(configMapNamespace, configMapName, null);
            transaction.setStatus(Transaction.SUCCESS);
        } catch (Throwable ex) {
            Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(ex));
            transaction.setStatus(ex);
            throw new ApolloConfigException("Create configmap failed!", ex);
        } finally {
            transaction.complete();
        }
    }

    @Override
    public Properties getConfig() {
        if (configMapProperties == null) {
            sync();
        }
        Properties result = propertiesFactory.getPropertiesInstance();
        result.putAll(configMapProperties);
        return result;
    }

    /**
     * Update the memory when the configuration center changes
     * @param upstreamConfigRepository the upstream repo
     */
    @Override
    public void setUpstreamRepository(ConfigRepository upstreamConfigRepository) {
        // 设置上游数据源
        if (upstreamConfigRepository == null) {
            return;
        }
        //clear previous listener
        if (upstream != null) {
            upstream.removeChangeListener(this);
        }
        upstream = upstreamConfigRepository;
        upstreamConfigRepository.addChangeListener(this);
    }

    @Override
    public ConfigSourceType getSourceType() {
        return sourceType;
    }

    /**
     * Sync the configmap
     */
    @Override
    protected void sync() {
        // 链式恢复，先从上游数据源读取
        boolean syncFromUpstreamResultSuccess = trySyncFromUpstream();

        if (syncFromUpstreamResultSuccess) {
            return;
        }

        Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "syncK8sConfigMap");
        Throwable exception = null;
        try {
            configMapProperties = loadFromK8sConfigMap();
            sourceType = ConfigSourceType.CONFIGMAP;
            transaction.setStatus(Transaction.SUCCESS);
        } catch (Throwable ex) {
            Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(ex));
            transaction.setStatus(ex);
            exception = ex;
            throw new ApolloConfigException("Load config from Kubernetes ConfigMap failed!", ex);
        } finally {
            transaction.complete();
        }

        if (configMapProperties == null) {
            sourceType = ConfigSourceType.NONE;
            throw new ApolloConfigException(
                    "Load config from Kubernetes ConfigMap failed!", exception);
        }
    }

    // 职责明确: manager层进行序列化和解析，把key传进去
    // repo这里只负责更新内存, Properties和appConfig格式的兼容
    public Properties loadFromK8sConfigMap() throws IOException {
        Preconditions.checkNotNull(configMapName, "ConfigMap name cannot be null");

        Properties properties = null;
        try {
            // 从ConfigMap获取整个配置信息的JSON字符串
            String jsonConfig = kubernetesManager.getValueFromConfigMap(configMapNamespace, configUtil.getAppId(), configMapKey);

            // 确保获取到的配置信息不为空
            if (jsonConfig != null) {
                // 解码Base64编码的JSON字符串
                jsonConfig = new String(Base64.getDecoder().decode(jsonConfig));
            }

            // 创建Properties实例
            properties = propertiesFactory.getPropertiesInstance();

            // 使用Gson将JSON字符串转换为Map对象
            if (jsonConfig != null && !jsonConfig.isEmpty()) {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> configMap = gson.fromJson(jsonConfig, type);
                // 将Map中的键值对填充到Properties对象中
                for (Map.Entry<String, String> entry : configMap.entrySet()) {
                    properties.setProperty(entry.getKey(), entry.getValue());
                }
            }
            return properties;
        } catch (Exception ex) {
            Tracer.logError(ex);
            throw new ApolloConfigException(String
                    .format("Load config from Kubernetes ConfigMap %s failed!", configMapName), ex);
        }
    }

    private boolean trySyncFromUpstream() {
        if (upstream == null) {
            return false;
        }
        try {
            // TODO 从上游数据恢复的逻辑
            // 拉新数据，并将新数据更新到configMap
            updateConfigMapProperties(upstream.getConfig(), upstream.getSourceType());
            return true;
        } catch (Throwable ex) {
            Tracer.logError(ex);
            logger
                    .warn("Sync config from upstream repository {} failed, reason: {}", upstream.getClass(),
                            ExceptionUtil.getDetailMessage(ex));
        }
        return false;
    }

    /**
     * Update the memory
     * @param namespace the namespace of this repository change
     * @param newProperties the properties after change
     */
    @Override
    public void onRepositoryChange(String namespace, Properties newProperties) {
        if (newProperties.equals(configMapProperties)) {
            return;
        }
        Properties newFileProperties = propertiesFactory.getPropertiesInstance();
        newFileProperties.putAll(newProperties);
        updateConfigMapProperties(newFileProperties, upstream.getSourceType());
        this.fireRepositoryChange(namespace, newProperties);
    }

    private synchronized void updateConfigMapProperties(Properties newProperties, ConfigSourceType sourceType) {
        this.sourceType = sourceType;
        if (newProperties.equals(configMapProperties)) {
            return;
        }
        this.configMapProperties = newProperties;
        persistConfigMap(configMapProperties);
    }

    public void persistConfigMap(Properties properties) {
        // 将Properties中的值持久化到configmap中，并使用事务管理
        Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "persistK8sConfigMap");
        transaction.addData("configMapName", configUtil.getAppId());
        transaction.addData("configMapNamespace", configUtil.getConfigMapNamespace());
        try {
            // 使用Gson将properties转换为JSON字符串
            Gson gson = new Gson();
            String jsonConfig = gson.toJson(properties);
            String encodedJsonConfig = Base64.getEncoder().encodeToString(jsonConfig.getBytes());
            // 创建一个新的HashMap, 将编码后的JSON字符串作为值，业务appId作为键，存入data中
            Map<String, String> data = new HashMap<>();
            data.put(configUtil.getAppId(), encodedJsonConfig);

            // 更新ConfigMap
            kubernetesManager.updateConfigMap(configUtil.getConfigMapNamespace(), configUtil.getAppId(), data);
            transaction.setStatus(Transaction.SUCCESS);
        } catch (Exception ex) {
            ApolloConfigException exception =
                    new ApolloConfigException(
                            String.format("Persist config to Kubernetes ConfigMap %s failed!", configMapName), ex);
            Tracer.logError(exception);
            transaction.setStatus(exception);
            logger.error("Persist config to Kubernetes ConfigMap failed!", exception);
        } finally {
            transaction.complete();
        }
        transaction.complete();
    }

}
