package core.framework.test.module;

import core.framework.impl.inject.BeanFactory;
import core.framework.impl.module.ModuleContext;

import java.util.Optional;

/**
 * @author neo
 */
public class TestModuleContext extends ModuleContext {
    TestModuleContext(BeanFactory beanFactory) {
        super(beanFactory);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> findConfig(Class<T> configClass, String name) {
        return Optional.ofNullable((T) configs.get(configClass.getCanonicalName() + ":" + name));
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Class<T> targetConfigClass(Class<T> configClass) {    // try to find override config class for test context
        String testConfigClass = configClass.getPackageName() + ".Test" + configClass.getSimpleName();
        try {
            return (Class<T>) Class.forName(testConfigClass);
        } catch (ClassNotFoundException e) {
            return configClass;
        }
    }
}