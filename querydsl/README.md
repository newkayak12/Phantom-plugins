# Phantom for QueryDSL
`dev.phantom.querydsl` · [한국어](./KOR.md)

QueryDSL's generated Q classes, followed back to the entities they come from.

- **Go to Declaration** on a Q class path goes to the entity instead of the generated code: `order.customer.name` to
  `Customer.name`, `order.id` to the `id` of the mapped superclass, `QOrder.order` to `Order`. In Java and Kotlin code.
- **Find Usages** of a Java entity field also finds its QueryDSL paths (`QOrder.order.name`), including those through
  the Q classes of subclasses of a mapped superclass; Find Usages of the entity finds `QOrder.order`.
- **Q class out of date:** a Java entity whose Q class was generated before it last changed is reported: a field with no
  path in the Q class (added since), or a path the Q class still has for a field that is gone. Build to regenerate.
  Static, `transient`, `@Transient` and `@QueryTransient` fields have no path and are not reported.

A Q class is a class named `Q…` extending one of QueryDSL's paths (`EntityPathBase`, `BeanPath`); its entity is the
type argument of that path. Q classes of Kotlin entities (kapt) are followed by Go to Declaration; Find Usages and the
out-of-date check need the entity in Java.
