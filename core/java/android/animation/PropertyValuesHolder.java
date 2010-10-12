/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.animation;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This class holds information about a property and the values that that property
 * should take on during an animation. PropertyValuesHolder objects can be used to create
 * animations with ValueAnimator or ObjectAnimator that operate on several different properties
 * in parallel.
 */
public class PropertyValuesHolder implements Cloneable {

    /**
     * The name of the property associated with the values. This need not be a real property,
     * unless this object is being used with ObjectAnimator. But this is the name by which
     * aniamted values are looked up with getAnimatedValue(String) in ValueAnimator.
     */
    private String mPropertyName;

    /**
     * The setter function, if needed. ObjectAnimator hands off this functionality to
     * PropertyValuesHolder, since it holds all of the per-property information. This
     * property can be manually set via setSetter(). Otherwise, it is automatically
     * derived when the animation starts in setupSetterAndGetter() if using ObjectAnimator.
     */
    private Method mSetter = null;

    /**
     * The getter function, if needed. ObjectAnimator hands off this functionality to
     * PropertyValuesHolder, since it holds all of the per-property information. This
     * property can be manually set via setSetter(). Otherwise, it is automatically
     * derived when the animation starts in setupSetterAndGetter() if using ObjectAnimator.
     * The getter is only derived and used if one of the values is null.
     */
    private Method mGetter = null;

    /**
     * The type of values supplied. This information is used both in deriving the setter/getter
     * functions and in deriving the type of TypeEvaluator.
     */
    private Class mValueType;

    /**
     * The set of keyframes (time/value pairs) that define this animation.
     */
    private KeyframeSet mKeyframeSet = null;


    // type evaluators for the three primitive types handled by this implementation
    private static final TypeEvaluator sIntEvaluator = new IntEvaluator();
    private static final TypeEvaluator sFloatEvaluator = new FloatEvaluator();
    private static final TypeEvaluator sDoubleEvaluator = new DoubleEvaluator();

    // We try several different types when searching for appropriate setter/getter functions.
    // The caller may have supplied values in a type that does not match the setter/getter
    // functions (such as the integers 0 and 1 to represent floating point values for alpha).
    // Also, the use of generics in constructors means that we end up with the Object versions
    // of primitive types (Float vs. float). But most likely, the setter/getter functions
    // will take primitive types instead.
    // So we supply an ordered array of other types to try before giving up.
    private static Class[] FLOAT_VARIANTS = {float.class, Float.class, double.class, int.class,
            Double.class, Integer.class};
    private static Class[] INTEGER_VARIANTS = {int.class, Integer.class, float.class, double.class,
            Float.class, Double.class};
    private static Class[] DOUBLE_VARIANTS = {double.class, Double.class, float.class, int.class,
            Float.class, Integer.class};

    // These maps hold all property entries for a particular class. This map
    // is used to speed up property/setter/getter lookups for a given class/property
    // combination. No need to use reflection on the combination more than once.
    private static final HashMap<Class, HashMap<String, Method>> sSetterPropertyMap =
            new HashMap<Class, HashMap<String, Method>>();
    private static final HashMap<Class, HashMap<String, Method>> sGetterPropertyMap =
            new HashMap<Class, HashMap<String, Method>>();

    // This lock is used to ensure that only one thread is accessing the property maps
    // at a time.
    private ReentrantReadWriteLock propertyMapLock = new ReentrantReadWriteLock();

    // Used to pass single value to varargs parameter in setter invocation
    private Object[] mTmpValueArray = new Object[1];

    /**
     * The type evaluator used to calculate the animated values. This evaluator is determined
     * automatically based on the type of the start/end objects passed into the constructor,
     * but the system only knows about the primitive types int, double, and float. Any other
     * type will need to set the evaluator to a custom evaluator for that type.
     */
    private TypeEvaluator mEvaluator;

    /**
     * The value most recently calculated by calculateValue(). This is set during
     * that function and might be retrieved later either by ValueAnimator.animatedValue() or
     * by the property-setting logic in ObjectAnimator.animatedValue().
     */
    private Object mAnimatedValue;

    /**
     * Internal utility constructor, used by the factory methods to set the property name.
     * @param propertyName The name of the property for this holder.
     */
    private PropertyValuesHolder(String propertyName) {
        mPropertyName = propertyName;
    }

    /**
     * Constructs and returns a PropertyValuesHolder with a given property name and
     * set of int values.
     * @param propertyName The name of the property being animated.
     * @param values The values that the named property will animate between.
     * @return PropertyValuesHolder The constructed PropertyValuesHolder object.
     */
    public static PropertyValuesHolder ofInt(String propertyName, int... values) {
        PropertyValuesHolder pvh = new PropertyValuesHolder(propertyName);
        pvh.setIntValues(values);
        Log.v("PVH", "ofFloat: propertyName: " + pvh.mPropertyName);
        return pvh;
    }

    /**
     * Constructs and returns a PropertyValuesHolder with a given property name and
     * set of float values.
     * @param propertyName The name of the property being animated.
     * @param values The values that the named property will animate between.
     * @return PropertyValuesHolder The constructed PropertyValuesHolder object.
     */
    public static PropertyValuesHolder ofFloat(String propertyName, float... values) {
        PropertyValuesHolder pvh = new PropertyValuesHolder(propertyName);
        pvh.setFloatValues(values);
        Log.v("PVH", "ofFloat: propertyName: " + pvh.mPropertyName);
        return pvh;
    }

    /**
     * Constructs and returns a PropertyValuesHolder with a given property name and
     * set of double values.
     * @param propertyName The name of the property being animated.
     * @param values The values that the named property will animate between.
     * @return PropertyValuesHolder The constructed PropertyValuesHolder object.
     */
    public static PropertyValuesHolder ofDouble(String propertyName, double... values) {
        PropertyValuesHolder pvh = new PropertyValuesHolder(propertyName);
        pvh.setDoubleValues(values);
        return pvh;
    }

    /**
     * Constructs and returns a PropertyValuesHolder with a given property name and
     * set of long values.
     * @param propertyName The name of the property being animated.
     * @param values The values that the named property will animate between.
     * @return PropertyValuesHolder The constructed PropertyValuesHolder object.
     */
    public static PropertyValuesHolder ofLong(String propertyName, long... values) {
        PropertyValuesHolder pvh = new PropertyValuesHolder(propertyName);
        pvh.setLongValues(values);
        return pvh;
    }

    /**
     * Constructs and returns a PropertyValuesHolder with a given property name and
     * set of Object values. This variant also takes a TypeEvaluator because the system
     * cannot interpolate between objects of unknown type.
     *
     * @param propertyName The name of the property being animated.
     * @param evaluator A TypeEvaluator that will be called on each animation frame to
     * provide the ncessry interpolation between the Object values to derive the animated
     * value.
     * @param values The values that the named property will animate between.
     * @return PropertyValuesHolder The constructed PropertyValuesHolder object.
     */
    public static PropertyValuesHolder ofObject(String propertyName, TypeEvaluator evaluator,
            Object... values) {
        PropertyValuesHolder pvh = new PropertyValuesHolder(propertyName);
        pvh.setObjectValues(values);
        pvh.setEvaluator(evaluator);
        return pvh;
    }

    /**
     * Constructs and returns a PropertyValuesHolder object with the specified property name and set
     * of values. These values can be of any type, but the type should be consistent so that
     * an appropriate {@link android.animation.TypeEvaluator} can be found that matches
     * the common type.
     * <p>If there is only one value, it is assumed to be the end value of an animation,
     * and an initial value will be derived, if possible, by calling a getter function
     * on the object. Also, if any value is null, the value will be filled in when the animation
     * starts in the same way. This mechanism of automatically getting null values only works
     * if the PropertyValuesHolder object is used in conjunction
     * {@link ObjectAnimator}, and with a getter function either
     * derived automatically from <code>propertyName</code> or set explicitly via
     * {@link #setGetter(java.lang.reflect.Method)}, since otherwise PropertyValuesHolder has
     * no way of determining what the value should be.
     * @param propertyName The name of the property associated with this set of values. This
     * can be the actual property name to be used when using a ObjectAnimator object, or
     * just a name used to get animated values, such as if this object is used with an
     * ValueAnimator object.
     * @param values The set of values to animate between.
     */
    public static PropertyValuesHolder ofKeyframe(String propertyName, Keyframe... values) {
        PropertyValuesHolder pvh = new PropertyValuesHolder(propertyName);
        pvh.setKeyframes(values);
        return pvh;
    }

    /**
     * Set the animated values for this object to this set of ints.
     * If there is only one value, it is assumed to be the end value of an animation,
     * and an initial value will be derived, if possible, by calling a getter function
     * on the object. Also, if any value is null, the value will be filled in when the animation
     * starts in the same way. This mechanism of automatically getting null values only works
     * if the PropertyValuesHolder object is used in conjunction
     * {@link ObjectAnimator}, and with a getter function either
     * derived automatically from <code>propertyName</code> or set explicitly via
     * {@link #setGetter(java.lang.reflect.Method)}, since otherwise PropertyValuesHolder has
     * no way of determining what the value should be.
     *
     * @param values One or more values that the animation will animate between.
     */
    public void setIntValues(int... values) {
        mValueType = int.class;
        mKeyframeSet = KeyframeSet.ofInt(values);
    }

    /**
     * Set the animated values for this object to this set of floats.
     * If there is only one value, it is assumed to be the end value of an animation,
     * and an initial value will be derived, if possible, by calling a getter function
     * on the object. Also, if any value is null, the value will be filled in when the animation
     * starts in the same way. This mechanism of automatically getting null values only works
     * if the PropertyValuesHolder object is used in conjunction
     * {@link ObjectAnimator}, and with a getter function either
     * derived automatically from <code>propertyName</code> or set explicitly via
     * {@link #setGetter(java.lang.reflect.Method)}, since otherwise PropertyValuesHolder has
     * no way of determining what the value should be.
     *
     * @param values One or more values that the animation will animate between.
     */
    public void setFloatValues(float... values) {
        mValueType = float.class;
        mKeyframeSet = KeyframeSet.ofFloat(values);
    }

    /**
     * Set the animated values for this object to this set of doubles.
     * If there is only one value, it is assumed to be the end value of an animation,
     * and an initial value will be derived, if possible, by calling a getter function
     * on the object. Also, if any value is null, the value will be filled in when the animation
     * starts in the same way. This mechanism of automatically getting null values only works
     * if the PropertyValuesHolder object is used in conjunction
     * {@link ObjectAnimator}, and with a getter function either
     * derived automatically from <code>propertyName</code> or set explicitly via
     * {@link #setGetter(java.lang.reflect.Method)}, since otherwise PropertyValuesHolder has
     * no way of determining what the value should be.
     *
     * @param values One or more values that the animation will animate between.
     */
    public void setDoubleValues(double... values) {
        mValueType = double.class;
        mKeyframeSet = KeyframeSet.ofDouble(values);
    }

    /**
     * Set the animated values for this object to this set of longs.
     * If there is only one value, it is assumed to be the end value of an animation,
     * and an initial value will be derived, if possible, by calling a getter function
     * on the object. Also, if any value is null, the value will be filled in when the animation
     * starts in the same way. This mechanism of automatically getting null values only works
     * if the PropertyValuesHolder object is used in conjunction
     * {@link ObjectAnimator}, and with a getter function either
     * derived automatically from <code>propertyName</code> or set explicitly via
     * {@link #setGetter(java.lang.reflect.Method)}, since otherwise PropertyValuesHolder has
     * no way of determining what the value should be.
     *
     * @param values One or more values that the animation will animate between.
     */
    public void setLongValues(long... values) {
        mValueType = long.class;
        mKeyframeSet = KeyframeSet.ofLong(values);
    }

    /**
     * Set the animated values for this object to this set of Keyframes.
     *
     * @param values One or more values that the animation will animate between.
     */
    public void setKeyframes(Keyframe... values) {
        int numKeyframes = values.length;
        Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes,2)];
        mValueType = ((Keyframe)values[0]).getType();
        for (int i = 0; i < numKeyframes; ++i) {
            keyframes[i] = (Keyframe)values[i];
        }
        mKeyframeSet = new KeyframeSet(keyframes);
    }

    /**
     * Set the animated values for this object to this set of Objects.
     * If there is only one value, it is assumed to be the end value of an animation,
     * and an initial value will be derived, if possible, by calling a getter function
     * on the object. Also, if any value is null, the value will be filled in when the animation
     * starts in the same way. This mechanism of automatically getting null values only works
     * if the PropertyValuesHolder object is used in conjunction
     * {@link ObjectAnimator}, and with a getter function either
     * derived automatically from <code>propertyName</code> or set explicitly via
     * {@link #setGetter(java.lang.reflect.Method)}, since otherwise PropertyValuesHolder has
     * no way of determining what the value should be.
     * 
     * @param values One or more values that the animation will animate between.
     */
    public void setObjectValues(Object... values) {
        mValueType = values[0].getClass();
        mKeyframeSet = KeyframeSet.ofObject(values);
    }

    /**
     * Determine the setter or getter function using the JavaBeans convention of setFoo or
     * getFoo for a property named 'foo'. This function figures out what the name of the
     * function should be and uses reflection to find the Method with that name on the
     * target object.
     *
     * @param targetClass The class to search for the method
     * @param prefix "set" or "get", depending on whether we need a setter or getter.
     * @param valueType The type of the parameter (in the case of a setter). This type
     * is derived from the values set on this PropertyValuesHolder. This type is used as
     * a first guess at the parameter type, but we check for methods with several different
     * types to avoid problems with slight mis-matches between supplied values and actual
     * value types used on the setter.
     * @return Method the method associated with mPropertyName.
     */
    private Method getPropertyFunction(Class targetClass, String prefix, Class valueType) {
        // TODO: faster implementation...
        Log.v("PVH", "getPropertyFunction: class, prefix, valueType, propertyName: " +
            targetClass + ", " + prefix + ", " + valueType + ", "+ mPropertyName);
        Method returnVal = null;
        String firstLetter = mPropertyName.substring(0, 1);
        String theRest = mPropertyName.substring(1);
        firstLetter = firstLetter.toUpperCase();
        String methodName = prefix + firstLetter + theRest;
        Class args[] = null;
        if (valueType == null) {
            try {
                returnVal = targetClass.getMethod(methodName, args);
            } catch (NoSuchMethodException e) {
                Log.e("PropertyValuesHolder",
                        "Couldn't find no-arg method for property " + mPropertyName + ": " + e);
            }
        } else {
            args = new Class[1];
            Class typeVariants[];
            if (mValueType.equals(Float.class)) {
                typeVariants = FLOAT_VARIANTS;
            } else if (mValueType.equals(Integer.class)) {
                typeVariants = INTEGER_VARIANTS;
            } else if (mValueType.equals(Double.class)) {
                typeVariants = DOUBLE_VARIANTS;
            } else {
                typeVariants = new Class[1];
                typeVariants[0] = mValueType;
            }
            for (Class typeVariant : typeVariants) {
                args[0] = typeVariant;
                try {
                    returnVal = targetClass.getMethod(methodName, args);
                    // change the value type to suit
                    mValueType = typeVariant;
                    return returnVal;
                } catch (NoSuchMethodException e) {
                    // Swallow the error and keep trying other variants
                }
            }
            // If we got here, then no appropriate function was found
            Log.e("PropertyValuesHolder",
                    "Couldn't find setter/getter for property " + mPropertyName +
                            "with value type "+ mValueType);
        }

        return returnVal;
    }


    /**
     * Returns the setter or getter requested. This utility function checks whether the
     * requested method exists in the propertyMapMap cache. If not, it calls another
     * utility function to request the Method from the targetClass directly.
     * @param targetClass The Class on which the requested method should exist.
     * @param propertyMapMap The cache of setters/getters derived so far.
     * @param prefix "set" or "get", for the setter or getter.
     * @param valueType The type of parameter passed into the method (null for getter).
     * @return Method the method associated with mPropertyName.
     */
    private Method setupSetterOrGetter(Class targetClass,
            HashMap<Class, HashMap<String, Method>> propertyMapMap,
            String prefix, Class valueType) {
        Method setterOrGetter = null;
        try {
            // Have to lock property map prior to reading it, to guard against
            // another thread putting something in there after we've checked it
            // but before we've added an entry to it
            // TODO: can we store the setter/getter per Class instead of per Object?
            propertyMapLock.writeLock().lock();
            HashMap<String, Method> propertyMap = propertyMapMap.get(targetClass);
            if (propertyMap != null) {
                setterOrGetter = propertyMap.get(mPropertyName);
            }
            if (setterOrGetter == null) {
                setterOrGetter = getPropertyFunction(targetClass, prefix, valueType);
                if (propertyMap == null) {
                    propertyMap = new HashMap<String, Method>();
                    propertyMapMap.put(targetClass, propertyMap);
                }
                propertyMap.put(mPropertyName, setterOrGetter);
            }
        } finally {
            propertyMapLock.writeLock().unlock();
        }
        return setterOrGetter;
    }

    /**
     * Utility function to get the setter from targetClass
     * @param targetClass The Class on which the requested method should exist.
     */
    private void setupSetter(Class targetClass) {
        mSetter = setupSetterOrGetter(targetClass, sSetterPropertyMap, "set", mValueType);
    }

    /**
     * Utility function to get the getter from targetClass
     */
    private void setupGetter(Class targetClass) {
        mGetter = setupSetterOrGetter(targetClass, sGetterPropertyMap, "get", null);
    }

    /**
     * Internal function (called from ObjectAnimator) to set up the setter and getter
     * prior to running the animation. If the setter has not been manually set for this
     * object, it will be derived automatically given the property name, target object, and
     * types of values supplied. If no getter has been set, it will be supplied iff any of the
     * supplied values was null. If there is a null value, then the getter (supplied or derived)
     * will be called to set those null values to the current value of the property
     * on the target object.
     * @param target The object on which the setter (and possibly getter) exist.
     */
    void setupSetterAndGetter(Object target) {
        Class targetClass = target.getClass();
        if (mSetter == null) {
            setupSetter(targetClass);
        }
        for (Keyframe kf : mKeyframeSet.mKeyframes) {
            if (kf.getValue() == null) {
                if (mGetter == null) {
                    setupGetter(targetClass);
                }
                try {
                    kf.setValue(mGetter.invoke(target));
                } catch (InvocationTargetException e) {
                    Log.e("PropertyValuesHolder", e.toString());
                } catch (IllegalAccessException e) {
                    Log.e("PropertyValuesHolder", e.toString());
                }
            }
        }
    }

    /**
     * Utility function to set the value stored in a particular Keyframe. The value used is
     * whatever the value is for the property name specified in the keyframe on the target object.
     *
     * @param target The target object from which the current value should be extracted.
     * @param kf The keyframe which holds the property name and value.
     */
    private void setupValue(Object target, Keyframe kf) {
        try {
            if (mGetter == null) {
                Class targetClass = target.getClass();
                setupGetter(targetClass);
            }
            kf.setValue(mGetter.invoke(target));
        } catch (InvocationTargetException e) {
            Log.e("PropertyValuesHolder", e.toString());
        } catch (IllegalAccessException e) {
            Log.e("PropertyValuesHolder", e.toString());
        }
    }

    /**
     * This function is called by ObjectAnimator when setting the start values for an animation.
     * The start values are set according to the current values in the target object. The
     * property whose value is extracted is whatever is specified by the propertyName of this
     * PropertyValuesHolder object.
     *
     * @param target The object which holds the start values that should be set.
     */
    void setupStartValue(Object target) {
        setupValue(target, mKeyframeSet.mKeyframes.get(0));
    }

    /**
     * This function is called by ObjectAnimator when setting the end values for an animation.
     * The end values are set according to the current values in the target object. The
     * property whose value is extracted is whatever is specified by the propertyName of this
     * PropertyValuesHolder object.
     *
     * @param target The object which holds the start values that should be set.
     */
    void setupEndValue(Object target) {
        setupValue(target, mKeyframeSet.mKeyframes.get(mKeyframeSet.mKeyframes.size() - 1));
    }

    @Override
    public PropertyValuesHolder clone() {
        ArrayList<Keyframe> keyframes = mKeyframeSet.mKeyframes;
        int numKeyframes = mKeyframeSet.mKeyframes.size();
        Keyframe[] newKeyframes = new Keyframe[numKeyframes];
        for (int i = 0; i < numKeyframes; ++i) {
            newKeyframes[i] = keyframes.get(i).clone();
        }
        return PropertyValuesHolder.ofKeyframe(mPropertyName, newKeyframes);
    }
    /**
     * Internal function to set the value on the target object, using the setter set up
     * earlier on this PropertyValuesHolder object. This function is called by ObjectAnimator
     * to handle turning the value calculated by ValueAnimator into a value set on the object
     * according to the name of the property.
     * @param target The target object on which the value is set
     */
    void setAnimatedValue(Object target) {
        if (mSetter != null) {
            try {
                mTmpValueArray[0] = mAnimatedValue;
                mSetter.invoke(target, mTmpValueArray);
            } catch (InvocationTargetException e) {
                Log.e("PropertyValuesHolder", e.toString());
            } catch (IllegalAccessException e) {
                Log.e("PropertyValuesHolder", e.toString());
            }
        }
    }

    /**
     * Internal function, called by ValueAnimator, to set up the TypeEvaluator that will be used
     * to calculate animated values.
     */
    void init() {
        if (mEvaluator == null) {
            mEvaluator = (mValueType == int.class || mValueType == Integer.class) ? sIntEvaluator :
                (mValueType == double.class || mValueType == Double.class) ? sDoubleEvaluator :
                        sFloatEvaluator;
        }
    }

    /**
     * The TypeEvaluator will the automatically determined based on the type of values
     * supplied to PropertyValuesHolder. The evaluator can be manually set, however, if so
     * desired. This may be important in cases where either the type of the values supplied
     * do not match the way that they should be interpolated between, or if the values
     * are of a custom type or one not currently understood by the animation system. Currently,
     * only values of type float, double, and int (and their Object equivalents, Float, Double,
     * and Integer) are  correctly interpolated; all other types require setting a TypeEvaluator.
     * @param evaluator
     */
	public void setEvaluator(TypeEvaluator evaluator) {
        mEvaluator = evaluator;
    }

    /**
     * Function used to calculate the value according to the evaluator set up for
     * this PropertyValuesHolder object. This function is called by ValueAnimator.animateValue().
     *
     * @param fraction The elapsed, interpolated fraction of the animation.
     * @return The calculated value at this point in the animation.
     */
    Object calculateValue(float fraction) {
        mAnimatedValue = mKeyframeSet.getValue(fraction, mEvaluator);
        return mAnimatedValue;
    }

    /**
     * Sets the <code>Method</code> that is called with the animated values calculated
     * during the animation. Setting the setter method is an alternative to supplying a
     * {@link #setPropertyName(String) propertyName} from which the method is derived. This
     * approach is more direct, and is especially useful when a function must be called that does
     * not correspond to the convention of <code>setName()</code>. For example, if a function
     * called <code>offset()</code> is to be called with the animated values, there is no way
     * to tell <code>ObjectAnimator</code> how to call that function simply through a property
     * name, so a setter method should be supplied instead.
     *
     * <p>Note that the setter function must take the same parameter type as the
     * <code>valueFrom</code> and <code>valueTo</code> properties, otherwise the call to
     * the setter function will fail.</p>
     *
     * @param setter The setter method that should be called with the animated values.
     */
    public void setSetter(Method setter) {
        mSetter = setter;
    }

    /**
     * Gets the <code>Method</code> that is called with the animated values calculated
     * during the animation.
     */
    public Method getSetter() {
        return mSetter;
    }

    /**
     * Sets the <code>Method</code> that is called to get unsupplied <code>valueFrom</code> or
     * <code>valueTo</code> properties. Setting the getter method is an alternative to supplying a
     * {@link #setPropertyName(String) propertyName} from which the method is derived. This
     * approach is more direct, and is especially useful when a function must be called that does
     * not correspond to the convention of <code>setName()</code>. For example, if a function
     * called <code>offset()</code> is to be called to get an initial value, there is no way
     * to tell <code>ObjectAnimator</code> how to call that function simply through a property
     * name, so a getter method should be supplied instead.
     *
     * <p>Note that the getter method is only called whether supplied here or derived
     * from the property name, if one of <code>valueFrom</code> or <code>valueTo</code> are
     * null. If both of those values are non-null, then there is no need to get one of the
     * values and the getter is not called.
     *
     * <p>Note that the getter function must return the same parameter type as the
     * <code>valueFrom</code> and <code>valueTo</code> properties (whichever of them are
     * non-null), otherwise the call to the getter function will fail.</p>
     *
     * @param getter The getter method that should be called to get initial animation values.
     */
    public void setGetter(Method getter) {
        mGetter = getter;
    }

    /**
     * Gets the <code>Method</code> that is called to get unsupplied <code>valueFrom</code> or
     * <code>valueTo</code> properties.
     */
    public Method getGetter() {
        return mGetter;
    }

    /**
     * Sets the name of the property that will be animated. This name is used to derive
     * a setter function that will be called to set animated values.
     * For example, a property name of <code>foo</code> will result
     * in a call to the function <code>setFoo()</code> on the target object. If either
     * <code>valueFrom</code> or <code>valueTo</code> is null, then a getter function will
     * also be derived and called.
     *
     * <p>Note that the setter function derived from this property name
     * must take the same parameter type as the
     * <code>valueFrom</code> and <code>valueTo</code> properties, otherwise the call to
     * the setter function will fail.</p>
     *
     * @param propertyName The name of the property being animated.
     */
    public void setPropertyName(String propertyName) {
        mPropertyName = propertyName;
    }

    /**
     * Gets the name of the property that will be animated. This name will be used to derive
     * a setter function that will be called to set animated values.
     * For example, a property name of <code>foo</code> will result
     * in a call to the function <code>setFoo()</code> on the target object. If either
     * <code>valueFrom</code> or <code>valueTo</code> is null, then a getter function will
     * also be derived and called.
     */
    public String getPropertyName() {
        return mPropertyName;
    }

    /**
     * Internal function, called by ValueAnimator and ObjectAnimator, to retrieve the value
     * most recently calculated in calculateValue().
     * @return
     */
    Object getAnimatedValue() {
        return mAnimatedValue;
    }
}