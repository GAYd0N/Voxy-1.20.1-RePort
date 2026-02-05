# Copyright 2025 MCRcortex

## All rights reserved.

Do not redistribute.

**THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.**

这只是为**1.20.1**编译的Voxy版本，包含了一些对整合包中各种模组的兼容性修复、性能优化以及稳定性改进。

### 主要变更
- **兼容性与稳定性修复**
    - 修复了在 Redis、LMDB 及内存存储后端读取数据时的缓冲区溢出风险，增加了容量检查并能正确抛出异常。
    - 修复了 RocksDB 迭代器未正确关闭的问题（使用 try-with-resources）。
    - 修复了区块数据摄取时的线程安全问题。
    - 修复了映射器中的状态处理和错误处理逻辑。
    - 修复了获取方块颜色时可能出现的空世界参数异常。（针对部分mod方块的修复）。
- **性能优化**
    - 优化渲染系统：将 `glFinish` 替换为 `glFlush` 并引入 GPU 围栏（Fence）同步，大幅减少 CPU 渲染等待时间，优化内存释放流程。
    - 优化体素化（Voxelization）过程：引入对象池复用，重构光照数据获取方式，减少内存分配开销。
- **构建与环境**
    - 稳定了 Fabric Loom 版本，移除特定的 CI 条件逻辑，确保构建的一致性。
    - 更新模组版本至 `0.2.6-alpha-polished.2`。
### 测试
经测试，使用信雅联接后在1.20.1版本的forge端目前较为正常，之前严重影响lod的bug也已修复，目前还在继续测试

---

This is a Voxy version compiled specifically for **1.20.1**, featuring compatibility fixes for various mods, performance optimizations, and stability improvements.

### Key Changes
- **Compatibility & Stability**
    - Fixed buffer overflow risks in Redis, LMDB, and Memory storage backends by adding capacity checks.
    - Fixed RocksDB iterator leaks using try-with-resources.
    - Resolved thread-safety issues during chunk data ingestion.
    - Improved state and error handling logic in the mapper.
    - Fixed null world parameter exceptions during block color retrieval.
- **Performance Optimizations**
    - Rendering: Replaced `glFinish` with `glFlush` and implemented GPU Fence synchronization to reduce CPU wait time and optimize memory release.
    - Voxelization: Optimized lighting data management with object pooling and refactored data access for better performance.
- **Build & Environment**
    - Stabilized Fabric Loom version and cleaned up build scripts for better reproducibility.
    - Updated mod version to `0.2.6-alpha-polished.2`.
