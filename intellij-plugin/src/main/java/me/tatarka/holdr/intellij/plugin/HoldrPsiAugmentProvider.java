package me.tatarka.holdr.intellij.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import me.tatarka.holdr.intellij.plugin.psi.HoldrLightClass;
import me.tatarka.holdr.intellij.plugin.psi.HoldrLightField;
import me.tatarka.holdr.intellij.plugin.psi.HoldrLightMethodBuilder;
import me.tatarka.holdr.model.Layout;
import me.tatarka.holdr.model.Listener;
import me.tatarka.holdr.model.Ref;
import me.tatarka.holdr.model.View;
import me.tatarka.holdr.util.GeneratorUtils;
import me.tatarka.holdr.util.GeneratorUtils.ListenerType;
import me.tatarka.holdr.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Created by evan on 2/13/15.
 */
public class HoldrPsiAugmentProvider extends PsiAugmentProvider {
    @SuppressWarnings("unchecked")
    @NotNull
    @Override
    public <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element, @NotNull Class<Psi> type) {
        if ((type != PsiClass.class && (type != PsiField.class && type != PsiMethod.class && type != PsiClass.class)) ||
                !(element instanceof PsiExtensibleClass)) {
            return Collections.emptyList();
        }

        PsiExtensibleClass aClass = (PsiExtensibleClass) element;

        HoldrModel holdrModel = HoldrModel.getInstance(aClass);
        if (holdrModel == null) {
            return Collections.emptyList();
        }

        if (!holdrModel.isHoldrClass(aClass)) {
            return Collections.emptyList();
        }

        boolean isListener = aClass.getName().equals("Listener");

        PsiExtensibleClass holdrClass;
        if (isListener) {
            holdrClass = (PsiExtensibleClass) aClass.getContainingClass();
        } else {
            holdrClass = aClass;
        }

        if (holdrClass == null) {
            return Collections.emptyList();
        }

        String layoutName = holdrModel.getLayoutName(holdrClass);
        Layout layout = HoldrLayoutManager.getInstance(holdrClass.getProject()).getLayout(layoutName);
        if (layout == null) {
            return Collections.emptyList();
        }

        final List<Psi> result = new ArrayList<Psi>();

        if (isListener) {
            if (type == PsiMethod.class) {
                final Set<String> existingListenerMethods = getOwnListenerMethods(aClass);
                final List<PsiMethod> newListenerMethods = buildListenerMethods(aClass, layout);
                for (PsiMethod method : newListenerMethods) {
                    if (!existingListenerMethods.contains(method.getName())) {
                        result.add((Psi) method);
                    }
                }
            }
        } else {
            if (type == PsiField.class) {
                final Set<String> existingFields = getOwnFields(aClass);
                final List<PsiField> newFields = buildFields(aClass, layout);
                for (PsiField field : newFields) {
                    if (!existingFields.contains(field.getName())) {
                        result.add((Psi) field);
                    }
                }
            } else if (type == PsiMethod.class) {
                if (!hasOwnListenerMethod(holdrClass)) {
                    PsiMethod listenerMethod = buildListenerMethod(holdrClass, layout);
                    if (listenerMethod != null) {
                        result.add((Psi) listenerMethod);
                    }
                }
            } else if (type == PsiClass.class) {
                if (!hasOwnListenerClass(holdrClass)) {
                    PsiClass listenerClass = buildListenerClass(holdrClass, layout);
                    if (listenerClass != null) {
                        result.add((Psi) listenerClass);
                    }
                }
            }
        }
        return result;
    }

    @NotNull
    private static Set<String> getOwnFields(@NotNull PsiExtensibleClass holdrClass) {
        final Set<String> result = new HashSet<String>();

        for (PsiField field : holdrClass.getOwnFields()) {
            result.add(field.getName());
        }
        return result;
    }

    private static boolean hasOwnListenerMethod(@NotNull PsiExtensibleClass holdrClass) {
        for (PsiMethod method : holdrClass.getOwnMethods()) {
            if (method.getName().equals("setListener")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOwnListenerClass(@NotNull PsiExtensibleClass holdrClass) {
        return !holdrClass.getOwnInnerClasses().isEmpty();
    }

    @NotNull
    private static Set<String> getOwnListenerMethods(@NotNull PsiExtensibleClass listenerClass) {
        final Set<String> result = new HashSet<String>();
        for (PsiMethod method : listenerClass.getOwnMethods()) {
            result.add(method.getName());
        }
        return result;
    }

    @NotNull
    private static List<PsiField> buildFields(@NotNull PsiClass context, @NotNull Layout layout) {
        final List<PsiField> result = new ArrayList<PsiField>();

        for (Ref ref : layout.getRefs()) {
            if (ref instanceof View) {
                View view = (View) ref;
                PsiType type = HoldrPsiUtils.findType(view.type, context.getProject());
                if (type == null) {
                    continue;
                }
                HoldrLightField field = new HoldrLightField(view.fieldName, context, type, view.isNullable, null);
                result.add(field);
            }
        }
        return result;
    }

    @Nullable
    private static PsiMethod buildListenerMethod(@NotNull PsiClass context, @NotNull Layout layout) {
        if (layout.getListeners().isEmpty()) {
            return null;
        }

        HoldrLightMethodBuilder method = new HoldrLightMethodBuilder("setListener", context);
        String listenerClassName = context.getQualifiedName() + ".Listener";
        PsiType listenerClassType = HoldrPsiUtils.findType(listenerClassName, context.getProject());
        if (listenerClassType == null) {
            PsiClass listenerClass = buildListenerClass(context, layout);
            if (listenerClass == null) {
                return null;
            }
            JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(context.getProject());
            listenerClassType = javaPsiFacade.getElementFactory().createType(listenerClass);
        }
        method.addParameter("listener", listenerClassType);
        return method;
    }

    @Nullable
    private static PsiClass buildListenerClass(@NotNull PsiClass context, @NotNull Layout layout) {
         if (layout.getListeners().isEmpty()) {
             return null;
         }
        HoldrLightClass listenerClass = new HoldrLightClass(context, "Listener");
        List<PsiMethod> methods = buildListenerMethods(listenerClass, layout);
        listenerClass.setMethods(methods);
        return listenerClass;
    }

    @NotNull
    private static List<PsiMethod> buildListenerMethods(@NotNull PsiClass context, @NotNull Layout layout) {
        List<PsiMethod> result = new ArrayList<PsiMethod>();

        for (Listener listener : layout.getListeners()) {
            HoldrLightMethodBuilder method = new HoldrLightMethodBuilder(listener.name, context)
                    .setAbstract(true);

            ListenerType listenerType = ListenerType.fromType(listener.type);

            for (Pair<GeneratorUtils.Type, String> param : listenerType.getParams()) {
                if (param.second.equals("view")) {
                    method.addParameter(param.second, HoldrPsiUtils.findType(listener.viewType, context.getProject()));
                } else {
                    method.addParameter(param.second, toPsiType(context.getProject(), listener.viewType, param.first));
                }
            }
            GeneratorUtils.Type returnType = listenerType.getReturnType();
            if (returnType != GeneratorUtils.Type.VOID) {
                method.setMethodReturnType(toPsiType(context.getProject(), listener.viewType, returnType));
            }
            result.add(method);
        }
        return result;
    }

    private static PsiType toPsiType(Project project, String viewType, GeneratorUtils.Type type) {
        switch (type) {
            case VIEW_CLASS: return HoldrPsiUtils.findType(viewType, project);
            case BOOLEAN: return PsiType.BOOLEAN;
            case INT: return PsiType.INT;
            case LONG: return PsiType.LONG;
            default: return HoldrPsiUtils.findType(type.getClassName(), project);
        }
    }
}
