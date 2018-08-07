Before:

```
[info] Silencer using global filters: bar\ in\ object\ Foo,lorem
[error] /path/to/src/main/scala/Test.scala:5:40: method find in trait GenericCollection is deprecated (since 0.16.0): Use `find` with optional `projection`
[error]   def foo(coll: BSONCollection) = coll.find(BSONDocument.empty)
[error]                                        ^
[error] one error found
```
