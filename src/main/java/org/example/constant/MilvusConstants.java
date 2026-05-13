package org.example.constant;

public class MilvusConstants {
    
    /**
     * Milvus 数据库名称
     */
    public static final String MILVUS_DB_NAME = "default";
    
    /**
     * Milvus 集合名称
     */
    public static final String MILVUS_COLLECTION_NAME = "biz";

    public static final String DOC_TYPE_DOCUMENT = "document";

    public static final String DOC_TYPE_CHAT_MEMORY = "chat_memory";

    public static final String DOCUMENT_FILTER_EXPR = "metadata[\"doc_type\"] == \"document\"";

    public static String chatMemoryFilterExpr(String sessionId) {
        return "metadata[\"doc_type\"] == \"chat_memory\" && metadata[\"session_id\"] == \""
                + escapeExprValue(sessionId) + "\"";
    }

    private static String escapeExprValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    
    /**
     * 向量维度（豆包 embedding 模型的维度）
     */
    public static final int VECTOR_DIM = 1024;  // 豆包模型返回1024维向量
    
    /**
     * ID字段最大长度
     */
    public static final int ID_MAX_LENGTH = 256;
    
    /**
     * Content字段最大长度
     */
    public static final int CONTENT_MAX_LENGTH = 8192;
    
    /**
     * 默认分片数
     */
    public static final int DEFAULT_SHARD_NUMBER = 2;
    
    private MilvusConstants() {
        // 工具类，禁止实例化
    }
}
