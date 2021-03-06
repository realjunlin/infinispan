package org.infinispan.loaders.jdbc.configuration;

import org.infinispan.configuration.cache.AsyncStoreConfiguration;
import org.infinispan.configuration.cache.LegacyConfigurationAdaptor;
import org.infinispan.configuration.cache.LegacyLoaderAdapter;
import org.infinispan.configuration.cache.SingletonStoreConfiguration;
import org.infinispan.loaders.jdbc.DatabaseType;
import org.infinispan.loaders.jdbc.mixed.JdbcMixedCacheStoreConfig;
import org.infinispan.commons.configuration.BuiltBy;
import org.infinispan.commons.util.TypedProperties;

/**
 *
 * JdbcMixedCacheStoreConfiguration.
 *
 * @author Tristan Tarrant
 * @since 5.2
 */
@BuiltBy(JdbcMixedCacheStoreConfigurationBuilder.class)
public class JdbcMixedCacheStoreConfiguration extends AbstractJdbcCacheStoreConfiguration implements LegacyLoaderAdapter<JdbcMixedCacheStoreConfig> {

   private final int batchSize;
   private final int fetchSize;
   private final DatabaseType databaseType;
   private final TableManipulationConfiguration binaryTable;
   private final TableManipulationConfiguration stringTable;
   private final String key2StringMapper;

   protected JdbcMixedCacheStoreConfiguration(int batchSize, int fetchSize, DatabaseType databaseType, String key2StringMapper, TableManipulationConfiguration binaryTable, TableManipulationConfiguration stringTable,
         ConnectionFactoryConfiguration connectionFactory, long lockAcquistionTimeout, int lockConcurrencyLevel, boolean purgeOnStartup, boolean purgeSynchronously,
         int purgerThreads, boolean fetchPersistentState, boolean ignoreModifications, TypedProperties properties, AsyncStoreConfiguration async,
         SingletonStoreConfiguration singletonStore) {
      super(connectionFactory, lockAcquistionTimeout, lockConcurrencyLevel, purgeOnStartup, purgeSynchronously, purgerThreads, fetchPersistentState, ignoreModifications,
            properties, async, singletonStore);
      this.databaseType = databaseType;
      this.batchSize = batchSize;
      this.fetchSize = fetchSize;
      this.key2StringMapper = key2StringMapper;
      this.binaryTable = binaryTable;
      this.stringTable = stringTable;
   }

   public String key2StringMapper() {
      return key2StringMapper;
   }

   public TableManipulationConfiguration binaryTable() {
      return binaryTable;
   }

   public TableManipulationConfiguration stringTable() {
      return stringTable;
   }

   public int batchSize() {
      return batchSize;
   }

   public int fetchSize() {
      return fetchSize;
   }

   public DatabaseType databaseType() {
      return databaseType;
   }

   @Override
   public JdbcMixedCacheStoreConfig adapt() {
      JdbcMixedCacheStoreConfig config = new JdbcMixedCacheStoreConfig();

      // StoreConfiguration
      LegacyConfigurationAdaptor.adapt(this, config);

      // ConnectionFactory
      ((LegacyConnectionFactoryAdaptor) connectionFactory()).adapt(config);

      // JdbcStringBasedCacheStoreConfiguration
      config.setKey2StringMapperClass(key2StringMapper);

      // TableManipulation
      config.setCreateTableOnStartForBinary(binaryTable().createOnStart());
      config.setDropTableOnExitForBinary(binaryTable().dropOnExit());
      config.setTableNamePrefixForBinary(binaryTable().tableNamePrefix());
      config.setDataColumnNameForBinary(binaryTable().dataColumnName());
      config.setDataColumnTypeForBinary(binaryTable().dataColumnType());
      config.setIdColumnNameForBinary(binaryTable().idColumnName());
      config.setIdColumnTypeForBinary(binaryTable().idColumnType());
      config.setTimestampColumnNameForBinary(binaryTable().timestampColumnName());
      config.setTimestampColumnTypeForBinary(binaryTable().timestampColumnType());

      config.setCreateTableOnStartForStrings(stringTable().createOnStart());
      config.setDropTableOnExitForStrings(stringTable().dropOnExit());
      config.setTableNamePrefixForStrings(stringTable().tableNamePrefix());
      config.setDataColumnNameForStrings(stringTable().dataColumnName());
      config.setDataColumnTypeForStrings(stringTable().dataColumnType());
      config.setIdColumnNameForStrings(stringTable().idColumnName());
      config.setIdColumnTypeForStrings(stringTable().idColumnType());
      config.setTimestampColumnNameForStrings(stringTable().timestampColumnName());
      config.setTimestampColumnTypeForStrings(stringTable().timestampColumnType());

      // Global TableManipulation settings
      config.setBatchSize(batchSize);
      config.setFetchSize(fetchSize);
      config.setDatabaseType(databaseType);

      return config;
   }

   @Override
   public String toString() {
      return "JdbcMixedCacheStoreConfiguration [binaryTable=" + binaryTable + ", stringTable=" + stringTable + ", key2StringMapper=" + key2StringMapper + ", connectionFactory()="
            + connectionFactory() + ", lockAcquistionTimeout()=" + lockAcquistionTimeout() + ", lockConcurrencyLevel()=" + lockConcurrencyLevel() + ", async()=" + async()
            + ", singletonStore()=" + singletonStore() + ", purgeOnStartup()=" + purgeOnStartup() + ", purgeSynchronously()=" + purgeSynchronously() + ", purgerThreads()="
            + purgerThreads() + ", fetchPersistentState()=" + fetchPersistentState() + ", ignoreModifications()=" + ignoreModifications() + ", properties()=" + properties() + "]";
   }



}