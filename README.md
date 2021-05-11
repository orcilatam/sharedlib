# Shared library para pipelines de Jenkins

Para facilitar la construcción de pipelines de Jenkins, se recomienda construir un _shared library_ (una biblioteca de funciones comunes compartidas) en Groovy para ser importadas por los `Jenkinsfiles`.

Estas funciones se pueden agrupar, por ejemplo, en un archivo [`Stage.groovy`](src/com/orcilatam/devops/Stage.groovy), que luego puede ser importado en un `Jenkinsfile` de la siguiente forma:

```groovy
@Library('sharedlib')
import static com.orcilatam.devops.Stage.*
```

Nótese que `sharedlib` es una referencia a un _Library Name_ cuyo repositorio origen de código debe estar descrito en _Manage Jenkins_ > _Configure System_ > _Global Pipeline Libraries_

Mantener los detalles de implementación de los pipelines en un shared library tiene dos ventajas:

1. El `Jenkinsfile` se mantiene conciso y legible; consta sólo de funciones de alto nivel
2. Las mejoras y bugfixes en la biblioteca están disponibles automática e instantáneamente para todos los pipelines

---

Copyright &copy; 2021 Marco Bravo, con licencia [GPL v3](LICENSE)
