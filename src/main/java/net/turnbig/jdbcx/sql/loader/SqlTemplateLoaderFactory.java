package net.turnbig.jdbcx.sql.loader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;

import freemarker.cache.StringTemplateLoader;
import net.turnbig.jdbcx.sql.loader.SqlTemplateLoaderFactory.SqlTemplateLoader;

/**
 * 

<h3>xml based template factory for freemarker</h3>

spring xml configuration sample:
<pre>
<bean id="xmlTemplate" class="com.woo.jdbcx.sql.loader.SqlTemplateLoaderFactory.SqlTemplateLoader" >
	<property name="locations">
		<list>
			<value>classpath:/templates/</value>
			<value>classpath:/template2/sample.xml</value>
		</list>
	</property>
</bean>

<bean id="freemarkerConfigurer" class="org.springframework.ui.freemarker.FreeMarkerConfigurationFactoryBean" 
	lazy-init="false">
	<property name="preTemplateLoaders">
		<list>
			<ref bean="xmlTemplate" />
		</list>
	</property>
	<property name="defaultEncoding" value="UTF-8" />
	<property name="freemarkerSettings">
		<props>
			<prop key="template_update_delay">0</prop>
		</props>
	</property>
</bean>
</pre>
 */
public class SqlTemplateLoaderFactory implements FactoryBean<SqlTemplateLoader>, InitializingBean {

	/**
	 * 
	 */
	private static final String DFT_RELOCATED_FOLDER_NAME = ".jdbcx";

	private static Logger logger = LoggerFactory.getLogger(SqlTemplateLoaderFactory.class);

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	private String[] locations;
	private SqlTemplateLoader sqlTemplateLoader = new SqlTemplateLoader();

	// when locations real path is jar:file: (not a plain file path)
	// we will copy those file "relocateTo"
	private String relocateTo;
	private String runtimeRelocateTo; // we use a new relocate folder based on current-time

	@Override
	public void afterPropertiesSet() throws Exception {
		createSqlTemplateLoader();
	}

	/**
	 * create relocate to folder
	 * 
	 * if relocate to base is not specified, use "${user.home}/.jdbcx/${now().toLong()}"
	 */
	protected String getRuntimeRelocateTo() {

		if (StringUtils.isBlank(relocateTo)) {
			relocateTo = System.getProperty("user.home") + File.separator + DFT_RELOCATED_FOLDER_NAME;
		}

		if (runtimeRelocateTo == null) {
			runtimeRelocateTo = relocateTo + File.separator + new Date().getTime();
			logger.info("Runtime relocate to path: {}", runtimeRelocateTo);
			new File(runtimeRelocateTo).mkdirs();
		}
		return runtimeRelocateTo;
	}

	/**
	 * @return 
	 * @throws IOException
	 */
	public SqlTemplateLoader createSqlTemplateLoader() throws IOException {
		for (String path : locations) {
			loadTemplates(path);
		}
		return sqlTemplateLoader;
	}

	/**
	 * load templates from a special path,
	 * 
	 * <li>classpath:templates/template1.xml</li>
	 * <li>classpath:templates/</li>
	 * 
	 * @param path
	 * @throws IOException
	 */
	public void loadTemplates(String path) throws IOException {
		// support of classpath:folder/*.xml
		Resource[] resources = ResourcePatternUtils.getResourcePatternResolver(resourceLoader).getResources(path);
		for (Resource r : resources) {
			if (r.exists()) {
				List<SqlTemplate> templates = new ArrayList<SqlTemplate>();
				try {
					templates = parseTemplate(r.getFile());
				} catch (Exception e) {
					// ignore all not XML file path
					String filename = r.getFilename();
					if (filename.endsWith(".xml")) {
						String copiedSqlTemplateFile = getRuntimeRelocateTo() + File.separator + filename;
						logger.debug("It seems {} is not a disk file, copied to {}", r.getURI(), copiedSqlTemplateFile);
						InputStream is = r.getInputStream();
						IOUtils.copy(is, new FileOutputStream(new File(copiedSqlTemplateFile)));
						IOUtils.closeQuietly(is);
						templates = parseTemplate(new File(copiedSqlTemplateFile));
					}
				}

				for (SqlTemplate xmlTemplate : templates) {
					sqlTemplateLoader.putTemplate(xmlTemplate.getName(), xmlTemplate.getTemplate(),
							xmlTemplate.getLastModified());
					sqlTemplateLoader.addMapper(sqlTemplateLoader.findTemplateSource(xmlTemplate.getName()),
							xmlTemplate.getTplFilePath());
				}
			}
		}
	}

	public static List<SqlTemplate> parseTemplate(File file) {
		List<SqlTemplate> result = new ArrayList<SqlTemplate>();
		if (file.isFile()) {
			logger.debug("load template from : {}", file.getAbsolutePath());
			SqlTemplates templates = SqlTemplateParser.fromXML(file);
			for (SqlTemplate sqlTemplate : templates.getTemplates()) {
				sqlTemplate.setLastModified(file.lastModified());
				sqlTemplate.setTplFilePath(file.getAbsolutePath());
				result.add(sqlTemplate);
			}
		} else if (file.isDirectory()) {
			logger.debug("load template from folder : {}", file.getAbsolutePath());
			File[] files = file.listFiles();
			for (File f : files) {
				result.addAll(parseTemplate(f));
			}
		}
		return result;
	}

	@Override
	public SqlTemplateLoader getObject() throws Exception {
		return sqlTemplateLoader;
	}

	@Override
	public Class<SqlTemplateLoader> getObjectType() {
		return SqlTemplateLoader.class;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public String[] getLocations() {
		return locations;
	}

	public void setLocations(String[] locations) {
		this.locations = locations;
	}

	public SqlTemplateLoader getSqlTemplateLoader() {
		return sqlTemplateLoader;
	}

	/**
	 * @param relocateTo the relocateTo to set
	 */
	public void setRelocateTo(String relocateTo) {
		this.relocateTo = relocateTo;
	}

	public static class SqlTemplateLoader extends StringTemplateLoader {

		private HashMap<Object, String> resourceMapper = new HashMap<Object, String>();

		/*
		 * (non-Javadoc)
		 * 
		 * @see freemarker.cache.StringTemplateLoader#findTemplateSource(java.lang.String)
		 */
		@Override
		public Object findTemplateSource(String name) {
			// reload template
			Object stringTemplateSource = super.findTemplateSource(name);
			if (stringTemplateSource != null && resourceMapper.containsKey(stringTemplateSource)) {
				String path = resourceMapper.get(stringTemplateSource);
				List<SqlTemplate> tpls = parseTemplate(new File(path));
				for (SqlTemplate xmlTemplate : tpls) {
					putTemplate(xmlTemplate.getName(), xmlTemplate.getTemplate(), xmlTemplate.getLastModified());
					addMapper(super.findTemplateSource(name), xmlTemplate.getTplFilePath());
				}
			}
			return super.findTemplateSource(name);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * freemarker.cache.StringTemplateLoader#getLastModified(java.lang.Object
		 * )
		 */
		@Override
		public long getLastModified(Object templateSource) {
			String path = resourceMapper.get(templateSource);
			File f = new File(path);
			return f.lastModified();
		}

		public void addMapper(Object object, String path) {
			this.resourceMapper.put(object, path);
		}

	}

	@XmlRootElement(name = "Templates")
	@XmlAccessorType(XmlAccessType.FIELD)
	public static class SqlTemplates {

		@XmlElement(name = "Template")
		List<SqlTemplate> templates = new ArrayList<SqlTemplate>();

		/**
		 * @return the templates
		 */
		public List<SqlTemplate> getTemplates() {
			return templates;
		}

		/**
		 * @param templates the templates to set
		 */
		public void setTemplates(List<SqlTemplate> templates) {
			this.templates = templates;
		}

	}

	@XmlRootElement(name = "Template")
	@XmlAccessorType(XmlAccessType.FIELD)
	public static class SqlTemplate {

		@XmlElement(name = "name")
		private String name;
		@XmlElement(name = "template")
		private String template;
		private long lastModified;
		private String tplFilePath;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getTemplate() {
			return template;
		}

		public void setTemplate(String template) {
			this.template = template;
		}

		public long getLastModified() {
			return lastModified;
		}

		public void setLastModified(long lastModified) {
			this.lastModified = lastModified;
		}

		@Override
		public String toString() {
			return "SqlTemplate [name=" + name + ", template=" + template + ", lastModified=" + lastModified + "]";
		}

		/**
		 * @return the tplFilePath
		 */
		public String getTplFilePath() {
			return tplFilePath;
		}

		/**
		 * @param tplFilePath
		 *            the tplFilePath to set
		 */
		public void setTplFilePath(String tplFilePath) {
			this.tplFilePath = tplFilePath;
		}

	}
}
