suite = {
  "mxversion" : "5.115.0",
  "name" : "graal-nodejs",
  "versionConflictResolution" : "latest",

  "imports" : {
    "suites" : [
      {
        "name" : "graal-js",
        "subdir" : True,
        "urls" : [
          {"url" : "https://curio.ssw.jku.at/nexus/content/repositories/snapshots", "kind" : "binary"},
        ]
      }
    ],
  },

  "developer" : {
    "name" : "Graal JS developers",
    "email" : "graal_js_ww_grp@oracle.com",
    "organization" : "Graal JS",
    "organizationUrl" : "https://labs.oracle.com/pls/apex/f?p=labs:49:::::P49_PROJECT_ID:129",
  },
  "url" : "http://www.oracle.com/technetwork/oracle-labs/program-languages/overview/index.html",

  "repositories" : {
    "graalnodejs-binary-snapshots" : {
      "url": "https://curio.ssw.jku.at/nexus/content/repositories/snapshots",
      "licenses" : ["Oracle Proprietary"]
    },
  },

  "licenses" : {
    "Oracle Proprietary" : {
      "name" : "ORACLE PROPRIETARY/CONFIDENTIAL",
      "url" : "http://www.oracle.com/us/legal/copyright/index.html"
    }
  },

  "defaultLicense" : "Oracle Proprietary",

  # If you add/remove/rename projects, update also the SharedBuild task of the gate
  "projects" : {
    "trufflenodeNative" : {
      "dependencies" : [
        "graal-js:GRAALJS",
        "coremodules",
      ],
      "class" : "GraalNodeJsProject",
      "output" : ".",
      "results" : [
        "out/Release/node",
        "common.gypi",
        "src/node.h",
        "src/node_buffer.h",
        "src/node_object_wrap.h",
        "src/node_version.h",
        "deps/uv/include",
        "deps/v8/include",
        "deps/v8/src/graal/graal_handle_content.h",
        "deps/npm",
      ],
    },
    "trufflenodeJNIConfig" : {
      "class" : "GraalNodeJsArchiveProject",
      "outputDir" : ".",
      "prefix": "",
      "results" : [
        "svmnodejs.jniconfig"
      ],
    },
    "com.oracle.truffle.trufflenode" : {
      "subDir" : "mx.graal-nodejs",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "graal-js:GRAALJS",
        "sdk:LAUNCHER_COMMON",
      ],
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "javaCompliance" : "1.8",
      "workingSets" : "Truffle,JavaScript,NodeJS",
    },
    "com.oracle.truffle.trufflenode.jniboundaryprofiler" : {
      "subDir" : "mx.graal-nodejs",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.trufflenode"
      ],
      "javaCompliance" : "1.8",
      "workingSets" : "Truffle,JavaScript,NodeJS",
    },
    "coremodules" : {
      "subDir" : "trufflenode",
      "buildDependencies" : [
        "graal-js:TRUFFLE_JS_SNAPSHOT_TOOL",
      ],
      "class" : "PreparsedCoreModulesProject",
      "prefix" : "",
      "outputDir" : "mxbuild/trufflenode/coremodules",
    },
  },

  "distributions" : {
    "TRUFFLENODE" : {
      "subdir" : "mx.graal-nodejs",
      "dependencies" : ["com.oracle.truffle.trufflenode"],
      "distDependencies" : [
        "graal-js:GRAALJS",
        "sdk:LAUNCHER_COMMON",
      ],
      "description" : "Graal Node.js",
      "maven" : {
        "artifactId" : "graal-nodejs",
      }
    },
    "TRUFFLENODE_JNI_BOUNDARY_PROFILER" : {
      "subdir" : "mx.graal-nodejs",
      "dependencies" : ["com.oracle.truffle.trufflenode.jniboundaryprofiler"],
      "distDependencies" : [
        "TRUFFLENODE"
      ],
      "description" : "Graal Node.js JNI Boundary Profiler Agent",
      "maven" : {
        "artifactId" : "graal-nodejs-jniboundaryprofiler",
      }
    },
    "TRUFFLENODE_NATIVE" : {
      "dependencies" : ["trufflenodeNative"],
      "native" : True,
      "platformDependent" : True,
      "relpath" : True,
      "description" : "Graal Node.js native components",
    },
    "TRUFFLENODE_JNICONFIG" : {
      "dependencies" : ["trufflenodeJNIConfig"],
      "native" : True,
      "relpath" : True,
      "description" : "Graal.nodejs JNI config file for SubstrateVM images",
      "maven" : {
        "artifactId" : "graal-nodejs-jniconfig",
      }
    },
  },
}
