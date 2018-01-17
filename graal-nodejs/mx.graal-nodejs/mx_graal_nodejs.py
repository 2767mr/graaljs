#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2007, 2015, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------

import mx, mx_gate, mx_graal_js, os, re, shutil, tarfile, tempfile

from mx import BinarySuite
from mx_gate import Task, Tags
from argparse import ArgumentParser
from os import remove, symlink, unlink
from os.path import dirname, exists, join, isdir, isfile, islink

_suite = mx.suite('graal-nodejs')
_currentOs = mx.get_os()
_currentArch = mx.get_arch()
_jdkHome = None
_svmNodeLibName = mx.add_lib_prefix('graaljs')
_svmNodeLibNameWithSuffix = mx.add_lib_suffix(_svmNodeLibName)

class GraalNodeJsTags:
    sharedBuild = 'sharedbuild'
    mvnPackage = 'mvnpackage'
    allTests = 'all'
    unitTests = 'unit'
    jniProfilerTests = 'jniprofiler'

def _graal_nodejs_pre_gate_runner(args, tasks):
    with Task('SharedBuildWithThreading', tasks, tags=[GraalNodeJsTags.sharedBuild, Tags.fullbuild]) as t:
        if t:
            cleanArgs = mx_gate.check_gate_noclean_arg(args)
            mx.command_function('clean')(cleanArgs)
            mx.command_function('build')(['--shared-library', '--threading'])

    with Task('ListFiles', tasks, tags=[GraalNodeJsTags.sharedBuild, Tags.fullbuild]) as t:
        if t:
            mx.run(['ls', join('out', 'Release')])
            mx.run(['ls', join('out', 'Release', 'obj.target')])
            mx.run(['ls', join('out', 'Release', 'lib.target')])

def _graal_nodejs_post_gate_runner(args, tasks):
    with Task('UnitTests', tasks, tags=[GraalNodeJsTags.allTests, GraalNodeJsTags.unitTests]) as t:
        if t:
            commonArgs = ['-ea']
            unitTestDir = join('test', 'graal')
            mx.run(['rm', '-rf', 'node_modules', 'build'], cwd=unitTestDir)
            npm(['--scripts-prepend-node-path=auto', 'install', '--nodedir=' + _suite.dir] + commonArgs, cwd=unitTestDir)
            npm(['--scripts-prepend-node-path=auto', 'test'] + commonArgs, cwd=unitTestDir)

    with Task('TestNpm', tasks, tags=[GraalNodeJsTags.allTests]) as t:
        if t:
            tmpdir = tempfile.mkdtemp()
            try:
                npm(['--scripts-prepend-node-path=auto', 'install', '--nodedir=' + _suite.dir, 'microtime'], cwd=tmpdir)
                node(['-e', 'print(require("microtime").now());'], cwd=tmpdir)
            finally:
                shutil.rmtree(tmpdir)

    with Task('JniProfilerTests', tasks, tags=[GraalNodeJsTags.allTests, GraalNodeJsTags.jniProfilerTests]) as t:
        if t:
            commonArgs = ['-ea']
            unitTestDir = join(mx.project('com.oracle.truffle.trufflenode.jniboundaryprofiler').dir, 'tests')
            mx.run(['rm', '-rf', 'node_modules', 'build'], cwd=unitTestDir)
            npm(['--scripts-prepend-node-path=auto', 'install', '--nodedir=' + _suite.dir] + commonArgs, cwd=unitTestDir)
            node(['-profile-native-boundary', '-Dtruffle.js.NashornJavaInterop=true', 'test.js'] + commonArgs, cwd=unitTestDir)

    with Task('MavenPackage', tasks, tags=[GraalNodeJsTags.mvnPackage]) as t:
        if t:
            mx.run(['mvn', '--batch-mode', '-f', join(_suite.dir, 'trufflenode', 'pom.xml'), 'package'])

mx_gate.prepend_gate_runner(_suite, _graal_nodejs_pre_gate_runner)
mx_gate.add_gate_runner(_suite, _graal_nodejs_post_gate_runner)

def _build(args, debug, shared_library, threading, parallelism, debug_mode):
    _mxrun(['./configure',
            '--partly-static',
            '--build-only-native',
            '--without-dtrace',
            '--without-snapshot',
            '--shared-graalvm', _getJdkHome(),
            '--shared-trufflejs', mx.distribution('graal-js:GRAALJS').path
        ] + debug + shared_library + threading,
        cwd=_suite.dir, verbose=True)

    _mxrun([mx.gmake_cmd(), '-j%d' % parallelism], cwd=_suite.dir, verbose=True)

    if _currentOs == 'darwin':
        nodePath = join(_suite.dir, 'out', 'Debug' if debug_mode else 'Release', 'node')
        _mxrun(['install_name_tool', '-add_rpath', join(_getJdkHome(), 'jre', 'lib'), nodePath], verbose=True)

    _createSymLinks()

class GraalNodeJsProject(mx.NativeProject):
    def __init__(self, suite, name, deps, workingSets, results, output, **args):
        self.suite = suite
        self.name = name
        mx.NativeProject.__init__(self, suite, name, '', [], deps, workingSets, results, output, suite.dir, **args)

    def getBuildTask(self, args):
        return GraalNodeJsBuildTask(self, args)

    def getResults(self, replaceVar=mx._replaceResultsVar):
        res = []
        for rt in self.results:
            r = re.sub(r'<(.+?)>', replaceVar, rt)
            result = join(self.suite.dir, self.getOutput(replaceVar), r)
            if exists(result):
                res.append(result)
            else:
                mx.log('GraalNodeJsProject %s in %s did not find %s' % (self.name, self.suite.name, result))
        return res

class GraalNodeJsBuildTask(mx.NativeBuildTask):
    def __init__(self, project, args):
        mx.NativeBuildTask.__init__(self, args, project)

    def build(self):
        pythonPath = join(_suite.mxDir, 'python2')
        prevPATH = os.environ['PATH']
        _setEnvVar('PATH', "%s:%s" % (pythonPath, prevPATH))

        debugMode = hasattr(self.args, 'debug') and self.args.debug
        debug = ['--debug'] if debugMode else []
        sharedlibrary = ['--enable-shared-library'] if hasattr(self.args, 'sharedlibrary') and self.args.sharedlibrary else []
        threading = ['--enable-threading'] if hasattr(self.args, 'threading') and self.args.threading else []

        _build(args=[], debug=debug, shared_library=sharedlibrary, threading=threading, parallelism=self.parallelism, debug_mode=debugMode)

    def needsBuild(self, newestInput):
        return (True, None) # Let make decide

    def clean(self, forBuild=False):
        if not forBuild:
            mx.run([mx.gmake_cmd(), 'clean'], nonZeroIsFatal=False, cwd=_suite.dir)
            _deleteTruffleNode()

class GraalNodeJsArchiveProject(mx.ArchivableProject):
    def __init__(self, suite, name, deps, workingSets, theLicense, **args):
        for attr in ['outputDir', 'prefix', 'results']:
            setattr(self, attr, args.pop(attr))
            if getattr(self, attr, None) is None:
                mx.abort("Missing '{}' attribute".format(attr), context="GraalNodeJsArchiveProject {}".format(name))
        mx.ArchivableProject.__init__(self, suite, name, deps, workingSets, theLicense)

    def output_dir(self):
        return join(self.dir, self.outputDir)

    def archive_prefix(self):
        return self.prefix

    def getResults(self, replaceVar=mx._replaceResultsVar):
        return [join(self.output_dir(), res) for res in self.results]

class PreparsedCoreModulesProject(mx.ArchivableProject):
    def __init__(self, suite, name, deps, workingSets, theLicense, **args):
        super(PreparsedCoreModulesProject, self).__init__(suite, name, deps, workingSets, theLicense)
        assert 'prefix' in args
        assert 'outputDir' in args

    def getBuildTask(self, args):
        return PreparsedCoreModulesBuildTask(self, args, 1)

    def output_dir(self):
        return self.get_output_root()

    def archive_prefix(self):
        return self.prefix

    def getResults(self):
        return [join(self.output_dir(), 'node_snapshots.h')]

class PreparsedCoreModulesBuildTask(mx.ArchivableBuildTask):
    def __str__(self):
        return 'Snapshotting {}'.format(self.subject)

    def newestInput(self):
        relInputPaths = [join('lib', m) for m in self.modulesToSnapshot()] + \
                        ['mx.graal-nodejs/mx_graal_nodejs.py',
                         'tools/js2c.py',
                         'tools/expand-js-macros.py',
                         'tools/snapshot2c.py',
                         'src/nolttng_macros.py',
                         'src/notrace_macros.py',
                         'src/noperfctr_macros.py']
        absInputPaths = [join(_suite.dir, p) for p in relInputPaths]
        return mx.TimeStampFile.newest(absInputPaths)

    def needsBuild(self, newestInput):
        localNewestInput = self.newestInput()
        if localNewestInput.isNewerThan(newestInput):
            newestInput = localNewestInput

        sup = mx.BuildTask.needsBuild(self, newestInput)
        if sup[0]:
            return sup
        reason = mx._needsUpdate(newestInput, self.subject.getResults()[0])
        if reason:
            return (True, reason)
        return (False, None)

    def modulesToSnapshot(self):
        if hasattr(self.args, "jdt") and self.args.jdt and not self.args.force_javac:
            return []

        brokenModules = [
            'internal/errors.js',               # Uses JSFunction.HOME_OBJECT_ID
            'internal/loader/ModuleJob.js',     # Uses await
            'internal/loader/ModuleMap.js',     # Uses JSFunction.HOME_OBJECT_ID
            'internal/loader/ModuleRequest.js', # Uses await
            'internal/loader/Loader.js',        # Uses await
            'internal/readline.js',             # Uses yield
            'internal/v8_prof_processor.js',    # Uses eval
            'module.js',                        # Uses await
        ]

        allModules = []
        modulePath = join(_suite.dir, 'lib')
        for root, _, files in os.walk(modulePath, followlinks=False):
            for name in (f for f in files if f.endswith('.js')):
                relname = os.path.relpath(join(root, name), modulePath)
                allModules.append(relname)

        return set(allModules).difference(set(brokenModules))

    def build(self):
        outputDir = self.subject.output_dir()
        snapshotToolDistribution = 'graal-js:TRUFFLE_JS_SNAPSHOT_TOOL'
        pythonCmd = join(_suite.mxDir, 'python2/python')

        moduleSet = self.modulesToSnapshot()

        outputDirBin = join(outputDir, 'lib')
        mx.ensure_dir_exists(outputDirBin)

        macroFiles = []
        # Lttng is disabled by default on all platforms
        macroFiles.append('src/nolttng_macros.py')
        # performance counters are enabled by default only on Windows
        if _currentOs is not 'windows':
            macroFiles.append('src/noperfctr_macros.py')
        # DTrace is disabled explicitly by the --without-dtrace option
        # ETW is enabled by default only on Windows
        if _currentOs is not 'windows':
            macroFiles.append('src/notrace_macros.py')

        mx.run([pythonCmd, 'tools/expand-js-modules.py', outputDir] + [join('lib', m) for m in moduleSet] + macroFiles,
               cwd=_suite.dir)
        if not (hasattr(self.args, "jdt") and self.args.jdt and not self.args.force_javac):
            mx.run_java(['-cp', mx.classpath([snapshotToolDistribution]), mx.distribution(snapshotToolDistribution).mainClass,
                     '--binary', '--outdir=' + outputDirBin, '--indir=' + outputDirBin] + ['--file=' + m for m in moduleSet],
                    cwd=outputDirBin)
        mx.run([pythonCmd, join(_suite.dir, 'tools/snapshot2c.py'), 'node_snapshots.h'] + [join('lib', m + '.bin') for m in moduleSet],
               cwd=outputDir)

    def clean(self, forBuild=False):
        outputDir = self.subject.output_dir()
        if os.path.exists(outputDir):
            mx.rmtree(outputDir)

def node_gyp(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    return node([join(_suite.dir, 'deps', 'npm', 'node_modules', 'node-gyp', 'bin', 'node-gyp.js')] + args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def npm(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    return node([join(_suite.dir, 'deps', 'npm', 'bin', 'npm-cli.js')] + args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def run_nodejs(vmArgs, runArgs, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    return node(vmArgs + runArgs, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def node(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    return mx.run(prepareNodeCmdLine(args), nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def testnode(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    mode, vmArgs, progArgs = setupNodeEnvironment(args)
    if mode == 'Debug':
        progArgs += ['-m', 'debug']
    _setEnvVar('NODE_JVM_OPTIONS', ' '.join(['-ea', '-Xrs'] + vmArgs))
    _setEnvVar('NODE_STACK_SIZE', '4000000')
    return mx.run([join(_suite.mxDir, 'python2', 'python'), 'tools/test.py'] + progArgs, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=(_suite.dir if cwd is None else cwd))

def setLibraryPath(additionalPath=None):
    javaHome = _getJdkHome()

    if _currentOs == 'darwin':
        libraryPath = join(javaHome, 'jre', 'lib')
    elif _currentOs == 'solaris':
        libraryPath = join(javaHome, 'jre', 'lib', 'sparcv9')
    elif _currentOs == 'linux' and _currentArch == 'sparcv9':
        libraryPath = join(javaHome, 'jre', 'lib', 'sparcv9')
    else:
        libraryPath = join(javaHome, 'jre', 'lib', 'amd64')

    if additionalPath:
        libraryPath += ':' + additionalPath

    if 'LD_LIBRARY_PATH' in os.environ:
        libraryPath += ':' + os.environ['LD_LIBRARY_PATH']

    _setEnvVar('LD_LIBRARY_PATH', libraryPath)

def setupNodeEnvironment(args):
    javaHome = _getJdkHome()
    _setEnvVar('JAVA_HOME', javaHome)
    if 'GRAALJS_JAR_PATH' not in os.environ:
        _setEnvVar('GRAALJS_JAR_PATH', mx.distribution('graal-js:GRAALJS').path)
    if mx.suite('compiler', fatalIfMissing=False) is None:
        _setEnvVar('GRAAL_SDK_JAR_PATH', mx.distribution('sdk:GRAAL_SDK').path)
        _setEnvVar('TRUFFLE_JAR_PATH', mx.distribution('truffle:TRUFFLE_API').path)
    _setEnvVar('TRUFFLE_DEBUG_JAR_PATH', mx.distribution('truffle:TRUFFLE_DEBUG').path)
    _setEnvVar('TRUFFLENODE_JAR_PATH', mx.distribution('TRUFFLENODE').path)
    setLibraryPath()

    prevPATH = os.environ['PATH']
    _setEnvVar('PATH', "%s:%s" % (join(_suite.mxDir, 'fake_launchers'), prevPATH))

    args = args if args else []
    mode, vmArgs, progArgs = _parseArgs(args)

    if mx.suite('graal-enterprise', fatalIfMissing=False):
        # explicitly require the enterprise compiler configuration
        vmArgs += ['-Dgraal.CompilerConfiguration=enterprise']
    if mx.suite('compiler', fatalIfMissing=False):
        vmArgs += ['-Djvmci.Compiler=graal', '-XX:+UnlockExperimentalVMOptions', '-XX:+EnableJVMCI']

    if isinstance(_suite, BinarySuite):
        mx.logv('%s is a binary suite' % _suite.name)
        tarfilepath = mx.distribution('TRUFFLENODE_NATIVE').path
        with tarfile.open(tarfilepath, 'r:') as tar:
            mx.logv('Extracting {} to {}'.format(tarfilepath, _suite.dir))
            tar.extractall(_suite.dir)

    return mode, vmArgs, progArgs

def makeInNodeEnvironment(args):
    argGroups = setupNodeEnvironment(args)
    _setEnvVar('NODE_JVM_OPTIONS', ' '.join(argGroups[1]))
    makeCmd = mx.gmake_cmd()
    if _currentOs == 'solaris':
        # we have to use GNU make and cp because the Solaris versions
        # do not support options used by Node.js Makefile and gyp files
        _setEnvVar('MAKE', makeCmd)
        _mxrun(['sh', '-c', 'ln -s `which gcp` ' + join(_suite.dir, 'cp')])
        prevPATH = os.environ['PATH']
        _setEnvVar('PATH', "%s:%s" % (_suite.dir, prevPATH))
    _mxrun([makeCmd] + argGroups[2], cwd=_suite.dir)
    if _currentOs == 'solaris':
        _mxrun(['rm', 'cp'])

def prepareNodeCmdLine(args):
    '''run a Node.js program or shell
        --debug to run in debug mode (provided that you build it)
    '''
    mode, vmArgs, progArgs = setupNodeEnvironment(args)
    _setEnvVar('NODE_JVM_OPTIONS', ' '.join(vmArgs))
    return [join(_suite.dir, 'out', mode, 'node')] + progArgs

def parse_js_args(args):
    vmArgs, progArgs = mx_graal_js.parse_js_args(args)

    profileJniArg = '-profile-native-boundary'
    if profileJniArg in progArgs:
        mx.log('Running with native profiling agent enabled. The argument is handled by mx and not passed as program argument')
        progArgs.remove(profileJniArg)
        vmArgs += ['-javaagent:' + join(_suite.dir, 'build', 'trufflenode-jniboundaryprofiler.jar')]

    return vmArgs, progArgs

def _mxrun(args, cwd=_suite.dir, verbose=False):
    if verbose:
        mx.log('Running \'{}\''.format(' '.join(args)))
    status = mx.run(args, nonZeroIsFatal=False, cwd=cwd)
    if status:
        mx.abort(status)

def _createSymLinks():
    def _createSymLinkToTruffleNode(dest):
        if not exists(dest):
            symlink(join(_suite.dir, mx.distribution('TRUFFLENODE').path), dest)

    # create symbolic links to trufflenode.jar
    _createSymLinkToTruffleNode(join(_suite.dir, 'trufflenode.jar'))
    for mode in ['Debug', 'Release']:
        destDir = join(_suite.dir, 'out', mode)
        if exists(destDir):
            _createSymLinkToTruffleNode(join(destDir, 'trufflenode.jar'))

def _deleteTruffleNode():
    def _delete(path):
        if islink(path):
            unlink(path)
        elif isfile(path):
            mx.logv('Warning! %s is a file, not a symlink, and will be deleted' % path)
            remove(path)
        elif isdir(path):
            mx.logv('Warning! %s is a directory, not a symlink, and will be deleted' % path)
            shutil.rmtree(path)

    _delete(join(_suite.dir, 'out'))
    _delete(join(_suite.dir, 'node'))
    _delete(join(_suite.dir, 'trufflenode.jar'))

def _setEnvVar(name, val):
    if val:
        mx.logv('Setting environment variable %s=%s' % (name, val))
        os.environ[name] = val

def _getJdkHome():
    global _jdkHome
    if not _jdkHome:
        _jdkHome = mx.get_env('JAVA_HOME')
    return _jdkHome

def _parseArgs(args):
    arguments = list(args)
    debugArg = '--debug'
    if debugArg in arguments:
        mx.log('Running in debug mode. The --debug argument is handled by mx and not passed as program argument')
        arguments.remove(debugArg)
        mode = 'Debug'
    else:
        mode = 'Release'

    vmArgs, progArgs = parse_js_args(arguments)
    if mx.suite('compiler', fatalIfMissing=False):
        import mx_compiler
        vmArgs = mx_compiler._parseVmArgs(vmArgs)

    if '-d64' in vmArgs:
        mx.logv('[_parseArgs] removing -d64 from vmArgs')
        vmArgs.remove('-d64')

    mx.logv('[_parseArgs] mode: %s' % mode)
    mx.logv('[_parseArgs] vmArgs: %s' % vmArgs)
    mx.logv('[_parseArgs] progArgs: %s' % progArgs)

    return mode, vmArgs, progArgs

def pylint(args):
    rcfile = join(dirname(mx.__file__), '.pylintrc')
    pythonpath = dirname(mx.__file__)
    for suite in mx.suites(True):
        pythonpath = os.pathsep.join([pythonpath, suite.mxDir])

    def findfiles_by_vc(pyfiles):
        for suite in mx.suites(True, includeBinary=False):
            if not suite.primary:
                continue
            files = suite.vc.locate(suite.mxDir, ['*.py'])
            for pyfile in files:
                pyfile = join(suite.mxDir, pyfile)
                if exists(pyfile):
                    pyfiles.append(pyfile)

    pyfiles = []
    findfiles_by_vc(pyfiles)
    env = os.environ.copy()
    env['PYTHONPATH'] = pythonpath

    for pyfile in pyfiles:
        mx.log('Running pylint on ' + pyfile + '...')
        mx.run(['pylint', '--reports=n', '--rcfile=' + rcfile, pyfile], env=env)

    return 0

def overrideBuild():
    def build(args):
        # add custom build arguments
        parser = ArgumentParser(prog='mx build')
        parser.add_argument('--debug', action='store_true', dest='debug', help='build in debug mode')
        parser.add_argument('--shared-library', action='store_true', dest='sharedlibrary', help='build as shared library')
        parser.add_argument('--threading', action='store_true', dest='threading', help='build with threading support')
        mx.build(args, parser)
        return 0

    mx.update_commands(_suite, {
        'build' : [build, ''],
    })

if _suite.primary:
    overrideBuild()

def buildSvmImage(args):
    javaArgs = []
    svmArgs = []
    for a in list(args):
        if a[0:3] in ("-R:", "-H:"):
            svmArgs.append(a)
        else:
            javaArgs.append(a)
    if len(javaArgs) > 0:
        mx.log("extra java args found: " + " ".join(javaArgs))
    if len(svmArgs) > 0:
        mx.log("extra svm args found: " + " ".join(svmArgs))
    mx.suite('substratevm').extensions.image([
            '-Dtruffle.js.DirectByteBuffer=true',
            '-Dpolyglot.js.v8-compatibility-mode=true',
            '-Dpolyglot.js.syntax-extensions=false',
            '-Dtruffle.js.U180EWhitespace=true',
            '-Dpolyglot.engine.PreinitializeContexts=js',
            ] + javaArgs + ['--',
            '-js',
            '-H:Kind=SHARED_LIBRARY',
            '-H:Name={}'.format(_svmNodeLibName),
            '-H:Projects=com.oracle.truffle.trufflenode',
            '-H:JNIConfigurationFiles=svmnodejs.jniconfig',
            '-H:+MultiThreaded',
            '-H:+JNICreateJavaVM',
            ] + svmArgs
        )

def preparesvmenv():
    setLibraryPath()
    _setEnvVar('NODE_JVM_LIB', join(_suite.dir, 'svmbuild', _svmNodeLibNameWithSuffix))

def testsvmnode(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    preparesvmenv()
    return mx.run([join(_suite.mxDir, 'python2', 'python'), 'tools/test.py'] + args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def svmnode(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    preparesvmenv()
    return mx.run([join(_suite.dir, 'node')] + args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

mx.update_commands(_suite, {
    'node' : [node, ''],
    'npm' : [npm, ''],
    'node-gyp' : [node_gyp, ''],
    'testnode' : [testnode, ''],
    'pylint' : [pylint, ''],
    'makeinnodeenv' : [makeInNodeEnvironment, ''],
    'buildsvmimage' : [buildSvmImage, ''],
    'svmnode' : [svmnode, ''],
    'testsvmnode' : [testsvmnode, ''],
})
