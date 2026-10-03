// Interactive Windows integration test. No production user window is captured.
#include <windows.h>
#include <d3d11.h>
#include <winrt/base.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
void pump() {
    MSG message {};
    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
        TranslateMessage(&message); DispatchMessageW(&message);
    }
}
class Window {
    winrt::com_ptr<ID3D11Device> device_;
    winrt::com_ptr<ID3D11DeviceContext> context_;
    winrt::com_ptr<IDXGISwapChain> swap_;
    winrt::com_ptr<ID3D11RenderTargetView> target_;
public:
    HWND handle = nullptr;
    static LRESULT CALLBACK procedure(HWND window, UINT message, WPARAM first, LPARAM second) {
        // The swap chain owns client pixels. A predefined STATIC control would
        // repaint its GDI background over a previously presented GPU color.
        if (message == WM_ERASEBKGND) return 1;
        if (message == WM_PAINT) {
            PAINTSTRUCT paint {};
            BeginPaint(window, &paint);
            EndPaint(window, &paint);
            return 0;
        }
        return DefWindowProcW(window, message, first, second);
    }
    explicit Window(const wchar_t* title) {
        static const ATOM registered = [] {
            WNDCLASSW type {};
            type.lpfnWndProc = procedure;
            type.hInstance = GetModuleHandleW(nullptr);
            type.lpszClassName = L"BossSyntheticGpuCaptureFixture";
            return RegisterClassW(&type);
        }();
        require(registered != 0, "synthetic GPU window class unavailable");
        handle = CreateWindowExW(WS_EX_NOACTIVATE, L"BossSyntheticGpuCaptureFixture", title, WS_OVERLAPPEDWINDOW,
            100, 100, 360, 270, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
        require(handle != nullptr, "synthetic window creation failed");
        DXGI_SWAP_CHAIN_DESC swap {};
        swap.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        swap.SampleDesc.Count = 1; swap.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        swap.BufferCount = 2; swap.OutputWindow = handle; swap.Windowed = TRUE;
        swap.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
        winrt::check_hresult(D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE,
            nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION,
            &swap, swap_.put(), device_.put(), nullptr, context_.put()));
        winrt::com_ptr<ID3D11Texture2D> back;
        winrt::check_hresult(swap_->GetBuffer(0, __uuidof(ID3D11Texture2D), back.put_void()));
        winrt::check_hresult(device_->CreateRenderTargetView(back.get(), nullptr, target_.put()));
        winrt::com_ptr<IDXGIAdapter> adapter;
        if (SUCCEEDED(device_.as<IDXGIDevice>()->GetAdapter(adapter.put()))) {
            DXGI_ADAPTER_DESC description {};
            if (SUCCEEDED(adapter->GetDesc(&description))) {
                std::cout << "Synthetic D3D adapter: " << winrt::to_string(description.Description)
                          << "; vendor=" << description.VendorId << "; device=" << description.DeviceId << '\n';
            }
        }
        ShowWindow(handle, SW_SHOWNOACTIVATE);
        UpdateWindow(handle);
        pump();
    }
    ~Window() { if (handle) DestroyWindow(handle); }
    void paint(float red, float green, float blue) {
        const float color[] = {red, green, blue, 1};
        context_->ClearRenderTargetView(target_.get(), color);
        winrt::check_hresult(swap_->Present(0, 0));
    }
};
class Child {
    HANDLE read_ = nullptr, control_ = nullptr, process_ = nullptr;
public:
    Child(const wchar_t* executable, HWND window, DWORD parent = GetCurrentProcessId(), int width = 160, int height = 120) {
        SECURITY_ATTRIBUTES attributes {sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
        HANDLE write = nullptr, input = nullptr;
        require(CreatePipe(&read_, &write, &attributes, 0) && CreatePipe(&input, &control_, &attributes, 0), "test pipes unavailable");
        SetHandleInformation(read_, HANDLE_FLAG_INHERIT, 0);
        SetHandleInformation(control_, HANDLE_FLAG_INHERIT, 0);
        HANDLE errors = nullptr;
        require(DuplicateHandle(GetCurrentProcess(), GetStdHandle(STD_ERROR_HANDLE), GetCurrentProcess(),
            &errors, 0, TRUE, DUPLICATE_SAME_ACCESS) != FALSE, "test diagnostic pipe unavailable");
        STARTUPINFOW startup {}; startup.cb = sizeof(startup); startup.dwFlags = STARTF_USESTDHANDLES;
        startup.hStdInput = input; startup.hStdOutput = write; startup.hStdError = errors;
        PROCESS_INFORMATION child {};
        std::wstring command = L"\"" + std::wstring(executable) + L"\" " + std::to_wstring(parent) + L" " +
            std::to_wstring(reinterpret_cast<uintptr_t>(window)) + L" " + std::to_wstring(width) + L" " + std::to_wstring(height) + L" 30";
        BOOL started = CreateProcessW(executable, command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW,
            nullptr, nullptr, &startup, &child);
        CloseHandle(input); CloseHandle(write); CloseHandle(errors);
        require(started != FALSE, "capture child failed to start");
        CloseHandle(child.hThread); process_ = child.hProcess;
    }
    ~Child() {
        if (control_) CloseHandle(control_);
        if (read_) CloseHandle(read_);
        if (process_) {
            if (WaitForSingleObject(process_, 1000) == WAIT_TIMEOUT) TerminateProcess(process_, 99);
            WaitForSingleObject(process_, 1000); CloseHandle(process_);
        }
    }
    void rate(uint32_t fps) {
        std::array<uint8_t, 4> bytes {0, 0, 0, static_cast<uint8_t>(fps)};
        DWORD sent = 0;
        require(WriteFile(control_, bytes.data(), 4, &sent, nullptr) && sent == 4, "rate change failed");
    }
    void read(uint8_t* target, size_t length) {
        const auto deadline = Clock::now() + std::chrono::seconds(8);
        size_t done = 0;
        while (done < length && Clock::now() < deadline) {
            pump();
            DWORD available = 0, received = 0;
            if (!PeekNamedPipe(read_, nullptr, 0, nullptr, &available, nullptr)) {
                DWORD status = 0;
                GetExitCodeProcess(process_, &status);
                std::cerr << "Capture child pipe ended: exit=" << status << "; bytes=" << done << '/' << length << '\n';
                throw std::runtime_error("capture stopped before a complete frame");
            }
            if (available) {
                const auto amount = static_cast<DWORD>(std::min<size_t>(available, length - done));
                require(ReadFile(read_, target + done, amount, &received, nullptr) && received, "frame read failed");
                done += received;
            } else {
                require(WaitForSingleObject(process_, 0) == WAIT_TIMEOUT, "capture child failed");
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
        }
        require(done == length, "capture frame timeout");
    }
    std::vector<uint8_t> frame(uint64_t& sequence) {
        std::array<uint8_t, 24> header {};
        read(header.data(), header.size());
        auto value = [&](size_t offset) {
            uint32_t result = 0;
            for (size_t index = offset; index < offset + 4; ++index) result = (result << 8) | header[index];
            return result;
        };
        require(std::memcmp(header.data(), "BSC1", 4) == 0 && value(4) == 160 && value(8) == 120 && value(12) == 160 * 120 * 4,
            "invalid bounded frame protocol");
        const uint64_t next = (static_cast<uint64_t>(value(16)) << 32) | value(20);
        require(next > sequence, "nonmonotonic frame sequence"); sequence = next;
        std::vector<uint8_t> pixels(value(12)); read(pixels.data(), pixels.size()); return pixels;
    }
    bool ended(DWORD timeout = 2000) { return WaitForSingleObject(process_, timeout) == WAIT_OBJECT_0; }
    bool rejected() {
        if (!ended()) return false;
        DWORD status = 0, available = 0;
        if (!GetExitCodeProcess(process_, &status) || status == 0) return false;
        const BOOL readable = PeekNamedPipe(read_, nullptr, 0, nullptr, &available, nullptr);
        return readable ? available == 0 : GetLastError() == ERROR_BROKEN_PIPE;
    }
    DWORD drainExit() {
        const auto deadline = Clock::now() + std::chrono::seconds(2);
        std::vector<uint8_t> pending;
        while (Clock::now() < deadline) {
            pump();
            DWORD available = 0, received = 0;
            const BOOL readable = PeekNamedPipe(read_, nullptr, 0, nullptr, &available, nullptr);
            if (readable && available) {
                require(pending.size() + available <= 4 * (24 + 160 * 120 * 4), "unbounded trailing capture data");
                const size_t offset = pending.size();
                pending.resize(offset + available);
                require(ReadFile(read_, pending.data() + offset, available, &received, nullptr) && received == available,
                    "trailing capture read failed");
            } else if (ended(0)) {
                DWORD status = 0;
                require(GetExitCodeProcess(process_, &status) != FALSE, "capture exit status unavailable");
                constexpr size_t packet = 24 + 160 * 120 * 4;
                require(pending.size() % packet == 0, "geometry exit truncated a frame");
                for (size_t offset = 0; offset < pending.size(); offset += packet) {
                    require(std::memcmp(pending.data() + offset, "BSC1", 4) == 0, "geometry exit corrupted framing");
                }
                return status;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        throw std::runtime_error("capture did not end after geometry changed");
    }
};
void assertColor(const std::vector<uint8_t>& pixels, int channel) {
    const auto* pixel = pixels.data() + (60 * 160 + 80) * 4;
    std::cout << "Captured synthetic center BGRA=" << unsigned(pixel[0]) << ',' << unsigned(pixel[1]) << ','
              << unsigned(pixel[2]) << ',' << unsigned(pixel[3]) << "; expected color channel=" << channel << '\n';
    require(pixel[channel] >= 200 && pixel[3] == 255, "GPU window color/opacity was not captured");
    for (int index = 0; index < 3; ++index) if (index != channel) require(pixel[index] <= 40, "unrelated occluder leaked into capture");
}
} // namespace

int wmain(int count, wchar_t** values) {
    try {
        require(count == 2, "pass the full helper executable path");
        // A default CI runner does not establish interactive capture fidelity.
        std::array<wchar_t, 8> enabled {};
        if (GetEnvironmentVariableW(L"BOSS_TEST_WINDOWS_CAPTURE", enabled.data(), static_cast<DWORD>(enabled.size())) != 1 || enabled[0] != L'1') {
            std::cout << "SKIP: set BOSS_TEST_WINDOWS_CAPTURE=1 in an unlocked interactive Windows session\n";
            return 77;
        }
        require(SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) != FALSE, "DPI mode unavailable");
        Window selected(L"Synthetic selected GPU source"); selected.paint(0, 1, 0);
        Window unrelated(L"Synthetic unrelated occluder"); unrelated.paint(1, 0, 0);
        SetWindowPos(unrelated.handle, HWND_TOPMOST, 80, 80, 420, 340, SWP_NOACTIVATE);
        pump();
        {
            Child rejected(values[1], selected.handle, GetCurrentProcessId() + 1);
            require(rejected.rejected(), "wrong parent must terminate without capture");
        }
        {
            Child rejected(values[1], GetDesktopWindow());
            require(rejected.rejected(), "desktop drawable must never be accepted as an owned window");
        }
        {
            Child rejected(values[1], selected.handle, GetCurrentProcessId(), 160, 160);
            require(rejected.rejected(), "mismatched native/requested aspect must terminate before any pixels");
        }
        {
            Child capture(values[1], selected.handle);
            uint64_t sequence = 0;
            assertColor(capture.frame(sequence), 1);
            capture.rate(60);
            selected.paint(0, 0, 1);
            bool blue = false;
            for (int frame = 0; frame < 20 && !blue; ++frame) {
                auto pixels = capture.frame(sequence);
                if (pixels[(60 * 160 + 80) * 4] > 200) { assertColor(pixels, 0); blue = true; }
            }
            require(blue, "accelerated source update did not reach the stream");
            // A fresh source frame ensures the writer becomes blocked while the
            // reader deliberately stops. The disposable helper must stop itself.
            selected.paint(0, 1, 0);
            require(capture.ended(1500), "blocked output did not terminate within its bounded deadline");
        }
        {
            Child capture(values[1], selected.handle);
            uint64_t sequence = 0; capture.frame(sequence);
            RECT bounds {};
            require(GetWindowRect(selected.handle, &bounds) != FALSE, "synthetic window bounds unavailable");
            require(SetWindowPos(selected.handle, nullptr, bounds.left + 12, bounds.top + 12, 0, 0,
                SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE) != FALSE, "synthetic window move failed");
            require(capture.drainExit() == 75, "live owned window movement must end at a clean geometry boundary");
        }
        {
            Child capture(values[1], selected.handle);
            uint64_t sequence = 0; capture.frame(sequence);
            DestroyWindow(selected.handle); selected.handle = nullptr;
            require(capture.drainExit() == 72, "destroyed selected window must remain terminal, never geometry retry");
        }
        std::cout << "PASS: exact occluded GPU content, live updates, bounded protocol/rate, parent binding, stalled output, window destruction\n";
        return 0;
    } catch (const winrt::hresult_error& error) {
        std::cerr << "Synthetic GPU fixture HRESULT 0x" << std::hex <<
            static_cast<uint32_t>(error.code().value) << '\n';
        return 1;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    } catch (...) {
        std::cerr << "Windows capture integration failed\n"; return 1;
    }
}
