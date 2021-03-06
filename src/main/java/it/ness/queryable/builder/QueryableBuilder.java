package it.ness.queryable.builder;

import it.ness.queryable.model.FilterDefBase;
import it.ness.queryable.model.QLikeListFilterDef;
import it.ness.queryable.model.QListFilterDef;
import it.ness.queryable.model.enums.FilterType;
import it.ness.queryable.templates.FreeMarkerTemplates;
import it.ness.queryable.util.GetSearchMethod;
import it.ness.queryable.util.ModelFiles;
import it.ness.queryable.util.StringUtil;
import org.apache.maven.plugin.logging.Log;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.MethodSource;

import java.io.File;
import java.io.FileWriter;
import java.util.*;

public class QueryableBuilder {

    protected Log log;
    protected StringUtil stringUtil;
    protected File outputDirectory;

    public QueryableBuilder(Log log, StringUtil stringUtil) {
        this.log = log;
        this.stringUtil = stringUtil;
        this.outputDirectory = new File("src/main/java");
    }

    public void generateSources(ModelFiles mf, String groupId) throws Exception {
        final String[] modelFiles = mf.getModelFileNames();
        final String path = mf.getPath();

        for (String modelFileName : modelFiles) {
            String className = stringUtil.getClassNameFromFileName(modelFileName);
            if (!mf.excludeClass(className)) {
                try {
                    createModel(mf, modelFileName);
                    String orderBy = mf.getDefaultOrderBy(className);
                    String rsPath = mf.getRsPath(className);
                    createRsService(mf, className, groupId, orderBy, rsPath);
                } catch (Exception e) {
                    log.error(e);
                }
            }
        }
    }

    private void createModel(ModelFiles mf, String modelFileName) throws Exception {
        String className = stringUtil.getClassNameFromFileName(modelFileName);
        final String path = mf.getPath();
        JavaClassSource javaClass = Roaster.parse(JavaClassSource.class, new File(path, modelFileName));

        Set<FilterDefBase> allFilderDefs = new LinkedHashSet<>();
        Set<FilterDefBase> preQueryDefs = mf.getFilterDef(className, FilterType.PREQUERY);
        Set<FilterDefBase> postQueryDefs = mf.getFilterDef(className, FilterType.POSTQUERY);
        if (null != preQueryDefs) {
            allFilderDefs.addAll(preQueryDefs);
        }
        if (null != postQueryDefs) {
            allFilderDefs.addAll(postQueryDefs);
        }

        if (allFilderDefs.size() == 0) {
            log.debug("Not defined Q filterdefs for class : " + className);
            return;
        }
        log.debug("Creating model for class : " + className);

        javaClass.addImport("org.hibernate.annotations.Filter");
        javaClass.addImport("org.hibernate.annotations.FilterDef");
        javaClass.addImport("org.hibernate.annotations.ParamDef");

        for (FilterDefBase fd : allFilderDefs) {
            fd.addAnnotationToModelClass(javaClass);
        }

        // remove imports if has org.hibernate.annotations.*
        if (javaClass.hasImport("org.hibernate.annotations")) {
            javaClass.removeImport("org.hibernate.annotations.Filter");
            javaClass.removeImport("org.hibernate.annotations.FilterDef");
            javaClass.removeImport("org.hibernate.annotations.ParamDef");
        }

        String packagePath = getPathFromPackage(javaClass.getPackage());
        File pd = new File(outputDirectory, packagePath);
        pd.mkdirs();

        FileWriter out = new FileWriter(new File(pd, modelFileName));
        try {
            out.append(javaClass.toString());
        } finally {
            out.flush();
            out.close();
        }
    }

    private void createRsService(ModelFiles mf, String className, String groupId, String
            orderBy, String rsPath) throws Exception {

        final Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("packageName", groupId);
        data.put("apiPackageName", groupId.substring(0, groupId.lastIndexOf('.')));
        data.put("className", className);
        data.put("rsPath", rsPath);
        data.put("defaultSort", orderBy);

        String serviceRsClass = FreeMarkerTemplates.processTemplate("servicers", data);
        JavaClassSource javaClassTemplate = Roaster.parse(JavaClassSource.class, serviceRsClass);
        Collection<FilterDefBase> preQueryFilters = mf.getFilterDef(className, FilterType.PREQUERY);
        Collection<FilterDefBase> postQueryFilters = mf.getFilterDef(className, FilterType.POSTQUERY);

        GetSearchMethod getSearchMethod = new GetSearchMethod(log, preQueryFilters, postQueryFilters, className);
        addImportsToClass(javaClassTemplate, preQueryFilters);
        addImportsToClass(javaClassTemplate, postQueryFilters);
        MethodSource<JavaClassSource> templateMethod = getMethodByName(javaClassTemplate, "getSearch");
        templateMethod.setBody(getSearchMethod.create());

        String packagePath = getPathFromPackage(javaClassTemplate.getPackage());
        File pd = new File(outputDirectory, packagePath);
        File filePath = new File(pd, className + "ServiceRs.java");
        if (filePath.exists()) {
            JavaClassSource javaClassOriginal = Roaster.parse(JavaClassSource.class, filePath);;
            // add imports to original
            addImportsToClass(javaClassOriginal, preQueryFilters);
            addImportsToClass(javaClassOriginal, postQueryFilters);
            MethodSource<JavaClassSource> method = getMethodByName(javaClassOriginal, "getSearch");
            if (method != null) {
                method.setBody(templateMethod.getBody());
            } else {
                javaClassOriginal.addMethod(templateMethod);
            }
            try (FileWriter out = new FileWriter(filePath)) {
                out.append(javaClassOriginal.toString());
            }
        } else {
            pd.mkdirs();
            try (FileWriter out = new FileWriter(filePath)) {
                out.append(javaClassTemplate.toString());
            }
        }
    }

    private void addImportsToClass(JavaClassSource javaClassSource, Collection<FilterDefBase> fd) {
        if (fd == null) return;
        for (FilterDefBase f : fd) {
            if ("LocalDateTime".equals(f.fieldType)) {
                javaClassSource.addImport("java.time.LocalDateTime");
            }
            if ("LocalDate".equals(f.fieldType)) {
                javaClassSource.addImport("java.time.LocalDate");
            }
            if ("BigDecimal".equals(f.fieldType)) {
                javaClassSource.addImport("java.math.BigDecimal");
            }
            if (f instanceof QListFilterDef) {
                javaClassSource.addImport("org.hibernate.Session");
            }
            if (f instanceof QLikeListFilterDef) {
                javaClassSource.addImport("java.util.HashMap");
                javaClassSource.addImport("java.util.Map");
            }
        }
    }

    private String getPathFromPackage(String packageName) {
        return packageName.replace(".", "/");
    }

    private MethodSource<JavaClassSource> getMethodByName(JavaClassSource javaClassSource, String name) {
        for (MethodSource<JavaClassSource> method : javaClassSource.getMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }
}
