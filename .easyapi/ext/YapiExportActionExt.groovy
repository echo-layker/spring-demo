import com.intellij.psi.*
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.PsiTypesUtil
import com.itangcent.common.constant.HttpMethod
import com.itangcent.common.exporter.ClassExporter
import com.itangcent.common.exporter.RequestHelper
import com.itangcent.common.exporter.RequestHelperKt
import com.itangcent.common.model.Header
import com.itangcent.common.model.Request
import com.itangcent.idea.plugin.api.export.AbstractClassExporter
import com.itangcent.idea.plugin.api.export.ClassExportRuleKeys
import com.itangcent.idea.plugin.api.export.yapi.YapiClassExportRuleKeys
import com.itangcent.idea.plugin.api.export.yapi.YapiRequestKitKt
import com.itangcent.idea.plugin.script.ActionExt
import com.itangcent.idea.plugin.utils.KtHelper
import com.itangcent.idea.plugin.utils.SpringClassName
import com.itangcent.intellij.context.ActionContext
import com.itangcent.intellij.psi.ClassRuleKeys
import com.itangcent.intellij.psi.PsiAnnotationUtils
import com.itangcent.intellij.util.KV
import org.apache.commons.lang3.StringUtils

import java.util.stream.Collectors
import java.util.stream.Stream

class YapiExportActionExt implements ActionExt {

    void init(ActionContext.ActionContextBuilder builder) {

        builder.bind(ClassExporter.class, KtHelper.INSTANCE.ktFunction({
            it.to(CustomClassExporter.class).in(com.google.inject.Singleton.class)
            return null
        }))

    }

    static class CustomClassExporter extends AbstractClassExporter {

        void processClass(PsiClass cls, KV<String, Object> kv) {
            logger.info("process class by ext:" + cls.name)

            PsiAnnotation ctrlRequestMappingAnn = findRequestMapping(cls)
            String basePath = findHttpPath(ctrlRequestMappingAnn) ?: ""

            String ctrlHttpMethod = findHttpMethod(ctrlRequestMappingAnn)

            kv.put("basePath", basePath)
            kv.put("ctrlHttpMethod", ctrlHttpMethod)
        }

        protected boolean hasApi(PsiClass psiClass) {
            return psiClass.annotations.any {
                SpringClassName.SPRING_CONTROLLER_ANNOTATION.contains(it.qualifiedName)
            }
        }

        boolean isApi(PsiMethod psiMethod) {
            return findRequestMappingInAnn(psiMethod) != null
        }

        void processMethodParameter(PsiMethod method, Request request, PsiParameter param, String paramDesc, RequestHelper requestHelper) {

            PsiAnnotation requestBodyAnn = findRequestBody(param)
            if (requestBodyAnn != null) {
                if (request.method == HttpMethod.NO_METHOD) {
                    requestHelper.setMethod(request, HttpMethod.POST)
                }
                RequestHelperKt.addHeader(requestHelper, request, "Content-Type", "application/json")
                requestHelper.setJsonBody(
                        request,
                        parseRequestBody(param.type, method),
                        paramDesc
                )
                return
            }

            PsiAnnotation modelAttrAnn = findModelAttr(param)
            if (modelAttrAnn != null) {
                if (request.method == HttpMethod.GET) {
                    addParamAsQuery(param, request, requestHelper)
                } else {
                    if (request.method == HttpMethod.NO_METHOD) {
                        requestHelper.setMethod(request, HttpMethod.POST)
                    }
                    addParamAsForm(param, request, requestHelper, null)
                }
                return
            }

            PsiAnnotation requestHeaderAnn = findRequestHeader(param)
            if (requestHeaderAnn != null) {

                String headName = PsiAnnotationUtils.INSTANCE.findAttr(requestHeaderAnn,
                        "value")
                if (StringUtils.isBlank(headName)) {
                    headName = PsiAnnotationUtils.INSTANCE.findAttr(requestHeaderAnn,
                            "name")
                }
                if (StringUtils.isBlank(headName)) {
                    headName = param.name
                }

                boolean required = findParamRequired(requestHeaderAnn) ?: true
                if (!required && ruleComputer.computer(ClassExportRuleKeys.INSTANCE.PARAM_REQUIRED, param) == true) {
                    required = true
                }

                String defaultValue = PsiAnnotationUtils.INSTANCE.findAttr(requestHeaderAnn,
                        "defaultValue")

                if (defaultValue == null
                        || defaultValue == SpringClassName.ESCAPE_REQUEST_HEADER_DEFAULT_NONE
                        || defaultValue == SpringClassName.REQUEST_HEADER_DEFAULT_NONE) {
                    defaultValue = ""
                }

                Header header = new Header()
                header.name = headName
                header.value = defaultValue
                header.example = defaultValue
                header.desc = paramDesc
                header.required = required
                requestHelper.addHeader(request, header)
                return
            }

            PsiAnnotation pathVariableAnn = findPathVariable(param)
            if (pathVariableAnn != null) {

                String pathName = PsiAnnotationUtils.INSTANCE.findAttr(pathVariableAnn,
                        "value")
                if (pathName == null) {
                    pathName = param.name
                }

                RequestHelperKt.addPathParam(requestHelper, request, pathName, paramDesc ?: "")
                return
            }

            String paramName = null
            Boolean required = false
            Object defaultVal = null

            PsiAnnotation requestParamAnn = findRequestParam(param)

            if (requestParamAnn != null) {
                paramName = findParamName(requestParamAnn)
                required = findParamRequired(requestParamAnn) ?: true

                defaultVal = PsiAnnotationUtils.INSTANCE.findAttr(requestParamAnn,
                        "defaultValue")

                if (defaultVal == null
                        || defaultVal == SpringClassName.ESCAPE_REQUEST_HEADER_DEFAULT_NONE
                        || defaultVal == SpringClassName.REQUEST_HEADER_DEFAULT_NONE) {
                    defaultVal = ""
                }
            }

            if (!required && ruleComputer.computer(ClassExportRuleKeys.INSTANCE.PARAM_REQUIRED, param) == true) {
                required = true
            }

            if (StringUtils.isBlank(paramName)) {
                paramName = param.name
            }

            PsiType paramType = param.type
            PsiType unboxType = psiClassHelper.unboxArrayOrList(paramType)
            PsiClass paramCls = PsiTypesUtil.getPsiClass(unboxType)
            if (unboxType instanceof PsiPrimitiveType) { //primitive Type
                if (defaultVal == null || defaultVal == "") {
                    defaultVal = PsiTypesUtil.getDefaultValue(unboxType)
                    //Primitive type parameter is required
                    //Optional primitive type parameter is present but cannot be translated into a null value due to being declared as a primitive type.
                    //Consider declaring it as object wrapper for the corresponding primitive type.
                    required = true
                }
            } else if (psiClassHelper.isNormalType(unboxType.canonicalText)) {//normal type
                if (defaultVal == null || defaultVal == "") {
                    defaultVal = psiClassHelper.getDefaultValue(unboxType.canonicalText)
                }
            } else if (paramCls != null && ruleComputer.computer(ClassRuleKeys.INSTANCE.TYPE_IS_FILE, paramCls) == true) {
                if (request.method == HttpMethod.GET) {
                    //can not upload file in a GET method
                    logger.error("Couldn't upload file in 'GET':[$request.method:${request.path}],param:${param.name} type:{${paramType.canonicalText}}")
                    return
                }

                if (request.method == HttpMethod.NO_METHOD) {
                    request.method = HttpMethod.POST
                }

                RequestHelperKt.addHeader(requestHelper, request, "Content-Type", "multipart/form-data")
                RequestHelperKt.addFormFileParam(requestHelper, request, paramName, required, paramDesc)
                return
            } else if (SpringClassName.SPRING_REQUEST_RESPONSE.contains(unboxType.presentableText)) {
                //ignore @HttpServletRequest and @HttpServletResponse
                return
            }

            if (defaultVal != null) {
                RequestHelperKt.addParam(requestHelper, request,
                        paramName
                        , defaultVal.toString()
                        , required
                        , paramDesc)
            } else {
                if (request.method == HttpMethod.GET) {
                    addParamAsQuery(param, request, requestHelper, paramDesc)
                } else {
                    if (request.method == HttpMethod.NO_METHOD) {
                        request.method = HttpMethod.POST
                    }
                    addParamAsForm(param, request, requestHelper, paramDesc)
                }
            }

        }

        void processMethod(PsiMethod method, KV<String, Object> kv, Request request, RequestHelper requestHelper) {

            super.processMethod(method, kv, request, requestHelper)

            String basePath = kv.getAs("basePath")
            String ctrlHttpMethod = kv.getAs("ctrlHttpMethod")
            PsiAnnotation requestMapping = findRequestMappingInAnn(method)
            String httpMethod = findHttpMethod(requestMapping)
            if (httpMethod == HttpMethod.NO_METHOD && ctrlHttpMethod != HttpMethod.NO_METHOD) {
                httpMethod = ctrlHttpMethod
            }
            request.method = httpMethod

            String httpPath = contractPath(basePath, findHttpPath(requestMapping))
            requestHelper.setPath(request, httpPath)
        }

        void processCompleted(PsiMethod method, Request request, RequestHelper requestHelper) {
            super.processCompleted(method, request, requestHelper)

            String tags = ruleComputer.computer(YapiClassExportRuleKeys.TAG, method)
            if (StringUtils.isNotBlank(tags)) {
                YapiRequestKitKt.setTags(request, Stream.of(tags.split("\n"))
                        .map { it.trim() }
                        .filter { StringUtils.isNotBlank(it) }
                        .collect(Collectors.toList()))
            }

            String status = ruleComputer.computer(YapiClassExportRuleKeys.STATUS, method)
            YapiRequestKitKt.setStatus(request, status)
        }

        private final String findHttpPath(PsiAnnotation requestMappingAnn) {
            String path = PsiAnnotationUtils.INSTANCE.findAttr((PsiAnnotation) requestMappingAnn, ["path", "value"] as String[])
            if (path != null) {
                return StringUtils.substringBefore(path, ",")
            } else {
                return null
            }
        }

        private final String findHttpMethod(PsiAnnotation requestMappingAnn) {
            if (requestMappingAnn != null) {
                String qualifiedName = requestMappingAnn.getQualifiedName()
                if (qualifiedName == "org.springframework.web.bind.annotation.RequestMapping") {
                    String method = PsiAnnotationUtils.INSTANCE.findAttr(requestMappingAnn, ["method"] as String[])
                    if (method != null) {
                        if (method.contains(",")) {
                            method = StringUtils.substringBefore(method, ",")
                        }

                        if (StringUtils.isBlank(method)) {
                            return "ALL"
                        }
                        if (method.startsWith("RequestMethod.")) {
                            return StringUtils.removeStart(method, "RequestMethod.")
                        }
                        if (method.contains("RequestMethod.")) {
                            return StringUtils.substringAfterLast(method, "RequestMethod.")
                        }

                        return method
                    }

                    return "ALL";
                }

                if (qualifiedName == "org.springframework.web.bind.annotation.GetMapping") {
                    return "GET"
                }
                if (qualifiedName == "org.springframework.web.bind.annotation.PostMapping") {
                    return "POST"
                }
                if (qualifiedName == "org.springframework.web.bind.annotation.DeleteMapping") {
                    return "DELETE"
                }

                if (qualifiedName == "org.springframework.web.bind.annotation.PatchMapping") {
                    return "PATCH"
                }
                if (qualifiedName == "org.springframework.web.bind.annotation.PutMapping") {
                    return "PUT"
                }
            }

            return "ALL"
        }

        private final PsiAnnotation findRequestMapping(PsiClass psiClass) {
            PsiAnnotation requestMappingAnn = this.findRequestMappingInAnn(psiClass)
            if (requestMappingAnn != null) {
                return requestMappingAnn
            } else {
                for (PsiClass superCls = psiClass.getSuperClass(); superCls != null; superCls = superCls.getSuperClass()) {
                    PsiAnnotation requestMappingAnnInSuper = this.findRequestMappingInAnn(superCls)
                    if (requestMappingAnnInSuper != null) {
                        return requestMappingAnnInSuper
                    }
                }

                return null
            }
        }

        private final PsiAnnotation findRequestMappingInAnn(PsiModifierListOwner ele) {
            for (String ann in SpringClassName.SPRING_REQUEST_MAPPING_ANNOTATIONS) {
                PsiAnnotation psiAnnotation = PsiAnnotationUtils.INSTANCE.findAnn(ele, ann)
                if (psiAnnotation != null) {
                    return psiAnnotation
                }
            }
            return null
        }

        private final PsiAnnotation findRequestBody(PsiParameter parameter) {
            return PsiAnnotationUtils.INSTANCE.findAnn(parameter, "org.springframework.web.bind.annotation.RequestBody")
        }

        private final PsiAnnotation findModelAttr(PsiParameter parameter) {
            return PsiAnnotationUtils.INSTANCE.findAnn(parameter, "org.springframework.web.bind.annotation.ModelAttribute")
        }

        private final PsiAnnotation findRequestHeader(PsiParameter parameter) {
            return PsiAnnotationUtils.INSTANCE.findAnn(parameter, "org.springframework.web.bind.annotation.RequestHeader")
        }

        private final PsiAnnotation findPathVariable(PsiParameter parameter) {
            return PsiAnnotationUtils.INSTANCE.findAnn(parameter, "org.springframework.web.bind.annotation.PathVariable")
        }

        private final PsiAnnotation findRequestParam(PsiParameter parameter) {
            return PsiAnnotationUtils.INSTANCE.findAnn(parameter, "org.springframework.web.bind.annotation.RequestParam")
        }

        private final String findParamName(PsiAnnotation requestParamAnn) {
            return PsiAnnotationUtils.INSTANCE.findAttr(requestParamAnn, ["name", "value"] as String[])
        }

        private final Boolean findParamRequired(PsiAnnotation requestParamAnn) {
            String required = PsiAnnotationUtils.INSTANCE.findAttr(requestParamAnn, ["required"] as String[]);
            if (required != null) {
                return !required.contains("false")
            } else {
                return null
            }
        }
    }
}


