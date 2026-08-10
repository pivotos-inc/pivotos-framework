package com.pivotos.ai.coding.service;

import com.pivotos.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.*;

/**
 * Plugin 骨架装配补丁器（S42 / 2.2-F12）。
 * <p>
 * 骨架产物落盘后，把新插件登记进工程装配四处（幂等，已登记则跳过）：
 * <ol>
 *   <li>pivotos-plugins/pom.xml —— modules 加双模块</li>
 *   <li>pivotos-dependencies/pom.xml —— dependencyManagement 加双模块版本仲裁
 *       （全仓约定：插件 artifact 版本一律 BOM 仲裁，引用方不写 version）</li>
 *   <li>pivotos-admin-server/pom.xml —— dependencies 加实现模块依赖</li>
 *   <li>PivotOsAdminApplication.java —— scanBasePackages 加 com.pivotos.{name}</li>
 * </ol>
 * 四处都是确定性文本插入；任一失败整体抛 7010，由调用方决定回滚（落盘文件可重入，git 可回滚）。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class AssemblyPatcher {

    private static final Logger log = LoggerFactory.getLogger(AssemblyPatcher.class);

    private static final String PLUGINS_POM = "pivotos-plugins/pom.xml";
    private static final String BOM_POM = "pivotos-dependencies/pom.xml";
    private static final String ADMIN_POM = "pivotos-admin-server/pom.xml";
    private static final String APP_CLASS = "pivotos-admin-server/src/main/java/com/pivotos/server/PivotOsAdminApplication.java";

    /**
     * 执行四处装配登记。
     *
     * @param frameworkRoot pivotos-framework 根目录（已探测确认）
     * @param pluginName    插件名
     * @param displayName   中文显示名（写进 pom description 注释）
     * @return 实际发生改动的文件相对路径清单
     */
    public List<String> patch(Path frameworkRoot, String pluginName, String displayName) {
        try {
            List<String> changed = new java.util.ArrayList<>();
            if (patchPluginsPom(frameworkRoot, pluginName)) {
                changed.add(PLUGINS_POM);
            }
            if (patchBomPom(frameworkRoot, pluginName)) {
                changed.add(BOM_POM);
            }
            if (patchAdminPom(frameworkRoot, pluginName)) {
                changed.add(ADMIN_POM);
            }
            if (patchScanBasePackages(frameworkRoot, pluginName)) {
                changed.add(APP_CLASS);
            }
            log.info("[AI Coding] Assembly patched: plugin={}, changed={}", pluginName, changed);
            return changed;
        } catch (IOException e) {
            log.error("[AI Coding] Assembly patch failed: plugin={}", pluginName, e);
            throw new ServiceException(CODING_ASSEMBLY_FAILED);
        }
    }

    /** ① 聚合 pom：双模块登记（插在 </modules> 前） */
    private boolean patchPluginsPom(Path root, String pluginName) throws IOException {
        Path pom = root.resolve(PLUGINS_POM);
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        String moduleApi = "<module>pivotos-plugin-" + pluginName + "-api</module>";
        String moduleImpl = "<module>pivotos-plugin-" + pluginName + "</module>";
        if (xml.contains(moduleImpl)) {
            return false;
        }
        String insertion = "        " + moduleApi + "\n        " + moduleImpl + "\n    </modules>";
        String patched = xml.replaceFirst("(?m)^\\s*</modules>", insertion);
        if (patched.equals(xml)) {
            throw new ServiceException(CODING_ASSEMBLY_FAILED);
        }
        Files.writeString(pom, patched, StandardCharsets.UTF_8);
        return true;
    }

    /** ② BOM：dependencyManagement 加双模块版本仲裁（插在 dependencyManagement 收尾前） */
    private boolean patchBomPom(Path root, String pluginName) throws IOException {
        Path pom = root.resolve(BOM_POM);
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        String artifact = "pivotos-plugin-" + pluginName;
        if (xml.contains(artifact)) {
            return false;
        }
        String block =
                "                <dependency>\n"
                        + "                    <groupId>com.pivotos</groupId>\n"
                        + "                    <artifactId>" + artifact + "-api</artifactId>\n"
                        + "                    <version>${project.version}</version>\n"
                        + "                </dependency>\n"
                        + "                <dependency>\n"
                        + "                    <groupId>com.pivotos</groupId>\n"
                        + "                    <artifactId>" + artifact + "</artifactId>\n"
                        + "                    <version>${project.version}</version>\n"
                        + "                </dependency>\n"
                        + "        </dependencies>\n"
                        + "    </dependencyManagement>";
        String anchor = "        </dependencies>\n    </dependencyManagement>";
        int idx = xml.lastIndexOf(anchor);
        if (idx < 0) {
            throw new ServiceException(CODING_ASSEMBLY_FAILED);
        }
        String patched = xml.substring(0, idx) + block + xml.substring(idx + anchor.length());
        Files.writeString(pom, patched, StandardCharsets.UTF_8);
        return true;
    }

    /** ③ admin-server pom：实现模块依赖（插在顶层 </dependencies> 前——首个出现，dependencyManagement 在其后） */
    private boolean patchAdminPom(Path root, String pluginName) throws IOException {
        Path pom = root.resolve(ADMIN_POM);
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        String artifact = "pivotos-plugin-" + pluginName;
        if (xml.contains(artifact)) {
            return false;
        }
        String block = "\n        <dependency>\n"
                + "            <groupId>com.pivotos</groupId>\n"
                + "            <artifactId>" + artifact + "</artifactId>\n"
                + "        </dependency>";
        // 顶层 dependencies 收尾必在 dependencyManagement/build 之前，取首个四层缩进 </dependencies>
        int idx = xml.indexOf("\n    </dependencies>");
        if (idx < 0) {
            throw new ServiceException(CODING_ASSEMBLY_FAILED);
        }
        String patched = xml.substring(0, idx) + block + xml.substring(idx);
        Files.writeString(pom, patched, StandardCharsets.UTF_8);
        return true;
    }

    /** ③ 启动类：scanBasePackages 追加 com.pivotos.{name} */
    private boolean patchScanBasePackages(Path root, String pluginName) throws IOException {
        Path appFile = root.resolve(APP_CLASS);
        String src = Files.readString(appFile, StandardCharsets.UTF_8);
        String pkg = "\"com.pivotos." + pluginName + "\"";
        if (src.contains(pkg)) {
            return false;
        }
        // 匹配 scanBasePackages = {...} 的收尾 "}"
        Pattern p = Pattern.compile("(scanBasePackages\\s*=\\s*\\{[^}]*)\\}");
        var m = p.matcher(src);
        if (!m.find()) {
            throw new ServiceException(CODING_ASSEMBLY_FAILED);
        }
        String patched = m.replaceFirst(java.util.regex.Matcher.quoteReplacement(m.group(1) + ", " + pkg + "}"));
        Files.writeString(appFile, patched, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * 探测 pivotos-framework 根目录：从 user.dir 起向上找含 pivotos-plugins/pom.xml 的目录（最多上溯 3 层）。
     */
    public Path resolveFrameworkRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            if (Files.exists(dir.resolve(PLUGINS_POM))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new ServiceException(CODING_ASSEMBLY_FAILED);
    }
}
