# Pekko + UIGC


This project is a fork of Apache Pekko with a new module called *UIGC*.
UIGC is a "UnIfied" Garbage Collection API for actors: you can use it
to create actors that will automatically be killed once no longer needed.

UIGC is designed to support multiple garbage collection "engines".
Currently, the project implements just two:

- *CRGC*: a distributed actor GC, capable of collecting cyclic garbage
  and recovering from crashed nodes and dropped messages.
- *WRC*: an implementation of weighted reference counting, capable
  of collecting acyclic garbage inside a single `ActorSystem`.

# Project Overview

The user-facing API for UIGC is located in
`uigc/src/main/scala/org/apache/pekko/uigc/actor`. Currently, it
reimplements a subset of the Typed Actors API.

The backend for UIGC is located in
`uigc/src/main/scala/org/apache/pekko/uigc/engines`. You can implement
a new actor GC by extending the `Engine` trait. For a (relatively)
simple example, look at the implementation of the `WRC` engine.

# License

Apache Pekko is available under the Apache License, version 2.0. See
[LICENSE](https://github.com/apache/pekko/blob/main/LICENSE) file for details.

Files in the `uigc` subproject are provided under the Hippocratic License,
version 3.0. See [LICENSE](./LICENSE) for details.
