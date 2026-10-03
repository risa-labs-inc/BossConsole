// Exact HWND capture. No desktop, PrintWindow, title lookup, or OS input fallback.
#include <windows.h>
#include <d3d11_4.h>
#include <d3dcompiler.h>
#include <dwmapi.h>
#include <tlhelp32.h>
#include <wtsapi32.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Metadata.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <future>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
using namespace winrt;
using namespace winrt::Windows::Graphics::Capture;
using winrt::Windows::Graphics::DirectX::DirectXPixelFormat;
using winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice;
using Clock = std::chrono::steady_clock;
constexpr GUID sessionDisplayStatus {0x2b84c20e, 0xad23, 0x4ddf, {0x93, 0xdb, 0x05, 0xff, 0xbd, 0x7e, 0xfc, 0xa5}};

void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
uint64_t millis() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        Clock::now().time_since_epoch()).count());
}
uint64_t number(const wchar_t* text) {
    require(text && *text, "missing numeric argument");
    uint64_t value = 0;
    for (auto current = text; *current; ++current) {
        require(*current >= L'0' && *current <= L'9', "invalid numeric argument");
        auto digit = static_cast<uint64_t>(*current - L'0');
        require(value <= (std::numeric_limits<uint64_t>::max() - digit) / 10, "numeric argument overflow");
        value = value * 10 + digit;
    }
    return value;
}
class Handle {
    HANDLE value_ = nullptr;
public:
    explicit Handle(HANDLE value = nullptr) : value_(value) {}
    ~Handle() { if (value_ && value_ != INVALID_HANDLE_VALUE) CloseHandle(value_); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    HANDLE get() const { return value_; }
};

DWORD actualParent() {
    Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
    require(snapshot.get() != INVALID_HANDLE_VALUE, "process ancestry unavailable");
    PROCESSENTRY32W entry {};
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot.get(), &entry)) {
        do {
            if (entry.th32ProcessID == GetCurrentProcessId()) return entry.th32ParentProcessID;
        } while (Process32NextW(snapshot.get(), &entry));
    }
    throw std::runtime_error("capture parent unavailable");
}

bool unlockedSession(DWORD session) {
    wchar_t* buffer = nullptr;
    DWORD bytes = 0;
    if (!WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, session, WTSSessionInfoEx, &buffer, &bytes)) return false;
    bool valid = false;
    if (bytes >= sizeof(WTSINFOEXW)) {
        const auto* info = reinterpret_cast<const WTSINFOEXW*>(buffer);
        valid = info->Level == 1 && info->Data.WTSInfoExLevel1.SessionId == session &&
            info->Data.WTSInfoExLevel1.SessionState == WTSActive &&
            info->Data.WTSInfoExLevel1.SessionFlags == WTS_SESSIONSTATE_UNLOCK;
    }
    WTSFreeMemory(buffer);
    // UAC/secure desktops and unknown input desktops are terminal, not blank frames
    // which could automatically start recording again after the user unlocks.
    HDESK desktop = OpenInputDesktop(0, FALSE, DESKTOP_READOBJECTS);
    if (!desktop) return false;
    std::array<wchar_t, 128> name {};
    DWORD required = 0;
    bool ordinary = GetUserObjectInformationW(desktop, UOI_NAME, name.data(),
        static_cast<DWORD>(sizeof(name)), &required) && _wcsicmp(name.data(), L"Default") == 0;
    CloseDesktop(desktop);
    return valid && ordinary;
}

// This guard owns a hidden notification window and an independent watchdog.
// A blocked synchronous anonymous-pipe write cannot defer lock/parent-death or
// its 250 ms deadline: the watchdog terminates this disposable helper process.
class Session {
    Handle parent_;
    DWORD session_ = 0;
    std::atomic<bool> stopping_ {false};
    std::atomic<bool> authorized_ {true};
    std::atomic<uint64_t> deadline_ {0};
    std::thread notifications_;
    std::thread watchdog_;

    static LRESULT CALLBACK procedure(HWND window, UINT message, WPARAM first, LPARAM second) {
        auto* self = reinterpret_cast<Session*>(GetWindowLongPtrW(window, GWLP_USERDATA));
        if (message == WM_NCCREATE) {
            self = static_cast<Session*>(reinterpret_cast<CREATESTRUCTW*>(second)->lpCreateParams);
            SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        }
        if (self && message == WM_WTSSESSION_CHANGE &&
            (first == WTS_SESSION_LOCK || first == WTS_SESSION_LOGOFF ||
             first == WTS_CONSOLE_DISCONNECT || first == WTS_REMOTE_DISCONNECT || first == WTS_SESSION_TERMINATE)) {
            self->authorized_.store(false);
        }
        if (self && message == WM_POWERBROADCAST &&
            (first == PBT_APMSUSPEND || first == PBT_APMRESUMEAUTOMATIC || first == PBT_APMRESUMESUSPEND)) {
            self->authorized_.store(false);
        }
        if (self && message == WM_POWERBROADCAST && first == PBT_POWERSETTINGCHANGE && second) {
            const auto* setting = reinterpret_cast<const POWERBROADCAST_SETTING*>(second);
            if (IsEqualGUID(setting->PowerSetting, sessionDisplayStatus)) {
                DWORD state = 0;
                if (setting->DataLength == sizeof(state)) std::memcpy(&state, setting->Data, sizeof(state));
                if (setting->DataLength != sizeof(state) || (state != 1 && state != 2)) self->authorized_.store(false);
            }
        }
        return DefWindowProcW(window, message, first, second);
    }
    void notifications(std::promise<bool> ready) noexcept {
        HWND window = nullptr;
        bool registered = false;
        HPOWERNOTIFY display = nullptr, sleep = nullptr;
        try {
            WNDCLASSW type {};
            type.lpfnWndProc = procedure;
            type.hInstance = GetModuleHandleW(nullptr);
            type.lpszClassName = L"BossExactCaptureSession";
            require(RegisterClassW(&type) != 0, "session notification class unavailable");
            // Message-only windows do not receive power broadcasts. This top-level
            // window is never shown, activated, or used as a capture source.
            window = CreateWindowExW(0, type.lpszClassName, L"", WS_POPUP, 0, 0, 0, 0,
                nullptr, nullptr, type.hInstance, this);
            require(window != nullptr, "session notification window unavailable");
            display = RegisterPowerSettingNotification(window, &sessionDisplayStatus, DEVICE_NOTIFY_WINDOW_HANDLE);
            sleep = RegisterSuspendResumeNotification(window, DEVICE_NOTIFY_WINDOW_HANDLE);
            require(display && sleep, "power authority notifications unavailable");
            registered = WTSRegisterSessionNotification(window, NOTIFY_FOR_THIS_SESSION) != FALSE;
            require(registered && unlockedSession(session_), "active unlocked session unavailable");
            ready.set_value(true);
            while (!stopping_.load() && authorized_.load()) {
                MSG message {};
                while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
                if (!unlockedSession(session_)) authorized_.store(false);
                MsgWaitForMultipleObjects(0, nullptr, FALSE, 20, QS_ALLINPUT);
            }
        } catch (...) {
            authorized_.store(false);
            try { ready.set_value(false); } catch (...) { }
        }
        if (display) UnregisterPowerSettingNotification(display);
        if (sleep) UnregisterSuspendResumeNotification(sleep);
        if (registered) WTSUnRegisterSessionNotification(window);
        if (window) DestroyWindow(window);
    }
public:
    explicit Session(DWORD parent)
        : parent_(OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, parent)) {
        require(parent > 0 && actualParent() == parent && parent_.get(), "capture must be a direct child of its window owner");
        DWORD parentSession = 0;
        require(ProcessIdToSessionId(GetCurrentProcessId(), &session_) && session_ != 0 &&
            ProcessIdToSessionId(parent, &parentSession) && parentSession == session_, "capture session mismatch");
        std::promise<bool> ready;
        auto started = ready.get_future();
        deadline_.store(millis() + 10000);
        notifications_ = std::thread([this, promise = std::move(ready)]() mutable { notifications(std::move(promise)); });
        watchdog_ = std::thread([this] {
            while (!stopping_.load()) {
                if (!authorized_.load() || WaitForSingleObject(parent_.get(), 0) != WAIT_TIMEOUT || millis() >= deadline_.load()) {
                    TerminateProcess(GetCurrentProcess(), 72);
                    return;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            }
        });
        if (started.wait_for(std::chrono::seconds(2)) != std::future_status::ready || !started.get()) {
            // A constructor cannot join a thread stuck in an unavailable OS service.
            TerminateProcess(GetCurrentProcess(), 72);
            throw std::runtime_error("session monitor unavailable");
        }
    }
    ~Session() {
        stopping_.store(true);
        if (watchdog_.joinable()) watchdog_.join();
        if (notifications_.joinable()) notifications_.join();
    }
    void budget(uint64_t milliseconds = 250) { deadline_.store(millis() + milliseconds); }
    void check() const {
        require(authorized_.load() && WaitForSingleObject(parent_.get(), 0) == WAIT_TIMEOUT &&
            unlockedSession(session_), "OS session or parent authority ended");
    }
};

void verifyWindow(HWND window, DWORD parent) {
    DWORD owner = 0;
    require(IsWindow(window) && GetWindowThreadProcessId(window, &owner) != 0 && owner == parent,
        "native window owner changed");
    require(GetAncestor(window, GA_ROOT) == window && IsWindowVisible(window) && !IsIconic(window),
        "capture requires the exact visible top-level window");
}
void supported() {
    require(GraphicsCaptureSession::IsSupported() &&
        winrt::Windows::Foundation::Metadata::ApiInformation::IsPropertyPresent(
            L"Windows.Graphics.Capture.GraphicsCaptureSession", L"IsCursorCaptureEnabled"),
        "Windows 10 version 2004 or newer with window capture is required");
}
void put32(uint8_t* bytes, uint32_t value) {
    for (int index = 3; index >= 0; --index) { bytes[index] = static_cast<uint8_t>(value & 255); value >>= 8; }
}
void writeFrame(const std::vector<uint8_t>& bytes, Session& session) {
    session.check();
    session.budget();
    HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
    require(output && output != INVALID_HANDLE_VALUE && GetFileType(output) == FILE_TYPE_PIPE,
        "capture requires an inherited anonymous output pipe");
    size_t offset = 0;
    while (offset < bytes.size()) {
        DWORD written = 0;
        require(WriteFile(output, bytes.data() + offset, static_cast<DWORD>(bytes.size() - offset), &written, nullptr) && written,
            "capture reader closed");
        offset += written;
        session.check();
    }
}

// GPU resize before readback: only the bounded publication pixels reach CPU memory.
// BGRA SDR is deliberate; HDR tone mapping requires a separate verified pipeline.
class Renderer {
    com_ptr<ID3D11Device> device_;
    com_ptr<ID3D11DeviceContext> context_;
    com_ptr<ID3D11Texture2D> sample_, output_, staging_;
    com_ptr<ID3D11ShaderResourceView> texture_;
    com_ptr<ID3D11RenderTargetView> target_;
    com_ptr<ID3D11VertexShader> vertex_;
    com_ptr<ID3D11PixelShader> pixel_;
    com_ptr<ID3D11SamplerState> sampler_;
    int width_, height_;
public:
    Renderer(int width, int height) : width_(width), height_(height) {
        check_hresult(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            nullptr, 0, D3D11_SDK_VERSION, device_.put(), nullptr, context_.put()));
        context_.as<ID3D11Multithread>()->SetMultithreadProtected(TRUE);
        static constexpr char shader[] =
            "struct V{float4 position:SV_POSITION;float2 uv:TEXCOORD0;};"
            "V vertex(uint id:SV_VertexID){V o;o.uv=float2((id<<1)&2,id&2);"
            "o.position=float4(o.uv*float2(2,-2)+float2(-1,1),0,1);return o;}"
            "Texture2D source:register(t0);SamplerState linearClamp:register(s0);"
            "float4 pixel(V i):SV_TARGET{return float4(source.Sample(linearClamp,i.uv).rgb,1);}";
        com_ptr<ID3DBlob> vertexCode, pixelCode;
        check_hresult(D3DCompile(shader, sizeof(shader) - 1, nullptr, nullptr, nullptr, "vertex", "vs_4_0", 0, 0, vertexCode.put(), nullptr));
        check_hresult(D3DCompile(shader, sizeof(shader) - 1, nullptr, nullptr, nullptr, "pixel", "ps_4_0", 0, 0, pixelCode.put(), nullptr));
        check_hresult(device_->CreateVertexShader(vertexCode->GetBufferPointer(), vertexCode->GetBufferSize(), nullptr, vertex_.put()));
        check_hresult(device_->CreatePixelShader(pixelCode->GetBufferPointer(), pixelCode->GetBufferSize(), nullptr, pixel_.put()));
        D3D11_SAMPLER_DESC sampler {};
        sampler.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
        sampler.AddressU = sampler.AddressV = sampler.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
        sampler.MaxLOD = D3D11_FLOAT32_MAX;
        check_hresult(device_->CreateSamplerState(&sampler, sampler_.put()));
        D3D11_TEXTURE2D_DESC output {};
        output.Width = static_cast<UINT>(width); output.Height = static_cast<UINT>(height);
        output.MipLevels = output.ArraySize = 1; output.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        output.SampleDesc.Count = 1; output.Usage = D3D11_USAGE_DEFAULT; output.BindFlags = D3D11_BIND_RENDER_TARGET;
        check_hresult(device_->CreateTexture2D(&output, nullptr, output_.put()));
        check_hresult(device_->CreateRenderTargetView(output_.get(), nullptr, target_.put()));
        output.Usage = D3D11_USAGE_STAGING; output.BindFlags = 0; output.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        check_hresult(device_->CreateTexture2D(&output, nullptr, staging_.put()));
    }
    IDirect3DDevice directDevice() const {
        auto dxgi = device_.as<IDXGIDevice>();
        com_ptr<IInspectable> wrapped;
        check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(), wrapped.put()));
        return wrapped.as<IDirect3DDevice>();
    }
    void read(const Direct3D11CaptureFrame& frame, const RECT& outer, const RECT& visible, std::vector<uint8_t>& bytes) {
        auto access = frame.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
        com_ptr<ID3D11Texture2D> source;
        check_hresult(access->GetInterface(__uuidof(ID3D11Texture2D), source.put_void()));
        D3D11_TEXTURE2D_DESC desc {};
        source->GetDesc(&desc);
        const auto size = frame.ContentSize();
        require(size.Width > 0 && size.Height > 0 && size.Width <= 8192 && size.Height <= 8192 &&
            desc.Width == static_cast<UINT>(size.Width) && desc.Height == static_cast<UINT>(size.Height) &&
            desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM && desc.SampleDesc.Count == 1 && desc.ArraySize == 1 && desc.MipLevels == 1,
            "capture surface geometry changed");
        RECT content = outer;
        if (size.Width != outer.right - outer.left || size.Height != outer.bottom - outer.top) {
            require(size.Width == visible.right - visible.left && size.Height == visible.bottom - visible.top,
                "capture coordinates cannot be bound to the selected window");
            content = visible;
        }
        if (!sample_) {
            desc.Usage = D3D11_USAGE_DEFAULT; desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
            desc.CPUAccessFlags = 0; desc.MiscFlags = 0;
            check_hresult(device_->CreateTexture2D(&desc, nullptr, sample_.put()));
            check_hresult(device_->CreateShaderResourceView(sample_.get(), nullptr, texture_.put()));
        }
        D3D11_TEXTURE2D_DESC sampled {};
        sample_->GetDesc(&sampled);
        require(sampled.Width == desc.Width && sampled.Height == desc.Height, "capture texture size changed");
        context_->CopyResource(sample_.get(), source.get());
        const float clear[] = {0, 0, 0, 1};
        context_->ClearRenderTargetView(target_.get(), clear);
        const float scaleX = static_cast<float>(width_) / static_cast<float>(outer.right - outer.left);
        const float scaleY = static_cast<float>(height_) / static_cast<float>(outer.bottom - outer.top);
        D3D11_VIEWPORT viewport {};
        viewport.TopLeftX = static_cast<float>(content.left - outer.left) * scaleX;
        viewport.TopLeftY = static_cast<float>(content.top - outer.top) * scaleY;
        viewport.Width = static_cast<float>(content.right - content.left) * scaleX;
        viewport.Height = static_cast<float>(content.bottom - content.top) * scaleY;
        viewport.MaxDepth = 1;
        context_->RSSetViewports(1, &viewport);
        ID3D11RenderTargetView* target = target_.get();
        ID3D11ShaderResourceView* texture = texture_.get();
        ID3D11SamplerState* sampler = sampler_.get();
        context_->OMSetRenderTargets(1, &target, nullptr);
        context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        context_->VSSetShader(vertex_.get(), nullptr, 0);
        context_->PSSetShader(pixel_.get(), nullptr, 0);
        context_->PSSetShaderResources(0, 1, &texture);
        context_->PSSetSamplers(0, 1, &sampler);
        context_->Draw(3, 0);
        texture = nullptr;
        context_->PSSetShaderResources(0, 1, &texture);
        context_->CopyResource(staging_.get(), output_.get());
        context_->Flush();
        D3D11_MAPPED_SUBRESOURCE mapped {};
        HRESULT result = DXGI_ERROR_WAS_STILL_DRAWING;
        const auto deadline = millis() + 150;
        while (result == DXGI_ERROR_WAS_STILL_DRAWING && millis() < deadline) {
            result = context_->Map(staging_.get(), 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &mapped);
            if (result == DXGI_ERROR_WAS_STILL_DRAWING) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        check_hresult(result);
        for (int y = 0; y < height_; ++y) {
            std::memcpy(bytes.data() + 24 + static_cast<size_t>(y) * width_ * 4,
                static_cast<const uint8_t*>(mapped.pData) + static_cast<size_t>(y) * mapped.RowPitch,
                static_cast<size_t>(width_) * 4);
        }
        context_->Unmap(staging_.get(), 0);
    }
};

int readFrameRate(int current) {
    HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    require(input && input != INVALID_HANDLE_VALUE && GetFileType(input) == FILE_TYPE_PIPE,
        "capture requires an inherited control pipe");
    DWORD available = 0;
    require(PeekNamedPipe(input, nullptr, 0, nullptr, &available, nullptr) != FALSE, "capture control pipe closed");
    require(available <= 64, "capture control queue overflow");
    // Never block on a partially written update; the next frame iteration retries.
    for (DWORD index = 0; index < available / 4; ++index) {
        std::array<uint8_t, 4> rate {};
        DWORD received = 0;
        require(ReadFile(input, rate.data(), 4, &received, nullptr) && received == 4, "capture control pipe closed");
        uint32_t value = 0;
        for (auto byte : rate) value = (value << 8) | byte;
        require(value == 30 || value == 60, "unsupported capture frame rate");
        current = static_cast<int>(value);
    }
    return current;
}

void stream(HWND window, DWORD parent, int width, int height, int fps, Session& authority) {
    verifyWindow(window, parent);
    RECT outer {}, visible {};
    require(GetWindowRect(window, &outer) && SUCCEEDED(DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS,
        &visible, sizeof(visible))), "physical window bounds unavailable");
    require(outer.right > outer.left && outer.bottom > outer.top && outer.right - outer.left <= 8192 &&
        outer.bottom - outer.top <= 8192 && visible.left >= outer.left && visible.top >= outer.top &&
        visible.right <= outer.right && visible.bottom <= outer.bottom, "invalid physical window bounds");
    // Requested dimensions derive from the authorized AWT geometry. Refuse DPI
    // disagreement instead of stretching native pixels over different input coordinates.
    const int64_t aspectError = static_cast<int64_t>(width) * (outer.bottom - outer.top) -
        static_cast<int64_t>(height) * (outer.right - outer.left);
    const int64_t rounding = std::max(outer.right - outer.left, outer.bottom - outer.top);
    require(aspectError >= -rounding && aspectError <= rounding, "native window aspect disagrees with requested geometry");
    auto factory = get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
    GraphicsCaptureItem item {nullptr};
    check_hresult(factory->CreateForWindow(window, guid_of<GraphicsCaptureItem>(), put_abi(item)));
    require(item.Size().Width > 0 && item.Size().Height > 0 && item.Size().Width <= 8192 && item.Size().Height <= 8192,
        "capture item exceeds source bounds");
    auto closed = std::make_shared<std::atomic<bool>>(false);
    auto closedToken = item.Closed([closed](const auto&, const auto&) { closed->store(true); });
    Renderer renderer(width, height);
    auto pool = Direct3D11CaptureFramePool::CreateFreeThreaded(renderer.directDevice(), DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, item.Size());
    auto capture = pool.CreateCaptureSession(item);
    capture.IsCursorCaptureEnabled(false);
    capture.StartCapture();
    std::vector<uint8_t> bytes(24 + static_cast<size_t>(width) * height * 4);
    std::memcpy(bytes.data(), "BSC1", 4);
    put32(bytes.data() + 4, static_cast<uint32_t>(width));
    put32(bytes.data() + 8, static_cast<uint32_t>(height));
    put32(bytes.data() + 12, static_cast<uint32_t>(bytes.size() - 24));
    uint64_t sequence = 0;
    try {
        for (;;) {
            const auto tick = Clock::now();
            authority.budget();
            authority.check();
            fps = readFrameRate(fps);
            verifyWindow(window, parent);
            RECT current {};
            require(!closed->load() && GetWindowRect(window, &current) && EqualRect(&current, &outer), "capture window changed");
            Direct3D11CaptureFrame latest {nullptr};
            // Free-threaded WGC keeps only two native buffers. Drain at most those
            // two; never let a faster producer create an unbounded processing loop.
            for (int count = 0; count < 2; ++count) {
                auto next = pool.TryGetNextFrame();
                if (!next) break;
                if (latest) latest.Close();
                latest = std::move(next);
            }
            if (latest) {
                renderer.read(latest, outer, visible, bytes);
                latest.Close();
                authority.check();
                verifyWindow(window, parent);
                require(!closed->load() && GetWindowRect(window, &current) && EqualRect(&current, &outer), "capture window changed during frame");
                ++sequence;
                put32(bytes.data() + 16, static_cast<uint32_t>(sequence >> 32));
                put32(bytes.data() + 20, static_cast<uint32_t>(sequence));
                writeFrame(bytes, authority);
            }
            std::this_thread::sleep_until(tick + std::chrono::microseconds(1000000 / fps));
        }
    } catch (...) {
        capture.Close();
        pool.Close();
        item.Closed(closedToken);
        throw;
    }
}
} // namespace

int wmain(int count, wchar_t** values) {
    try {
        require(count == 3 || count == 6, "usage: boss-app-capture PID HWND WIDTH HEIGHT FPS or --probe/--watch PID");
        const bool probe = count == 3 && std::wstring(values[1]) == L"--probe";
        const bool watch = count == 3 && std::wstring(values[1]) == L"--watch";
        require(count == 6 || probe || watch, "unknown capture mode");
        const auto parentValue = number(values[count == 3 ? 2 : 1]);
        require(parentValue > 0 && parentValue <= std::numeric_limits<DWORD>::max(), "invalid parent process");
        require(SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) != FALSE,
            "physical pixel DPI awareness unavailable");
        Session authority(static_cast<DWORD>(parentValue));
        init_apartment(apartment_type::multi_threaded);
        supported();
        if (probe) { authority.check(); return 0; }
        if (watch) {
            for (;;) {
                authority.budget(); authority.check();
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
        }
        const auto handle = number(values[2]), width = number(values[3]), height = number(values[4]), fps = number(values[5]);
        require(handle > 0 && handle <= std::numeric_limits<uintptr_t>::max() && width > 0 && height > 0 &&
            width <= 1920 && height <= 1920 && width * height <= 4194304 && (fps == 30 || fps == 60), "invalid capture dimensions or rate");
        stream(reinterpret_cast<HWND>(static_cast<uintptr_t>(handle)), static_cast<DWORD>(parentValue),
            static_cast<int>(width), static_cast<int>(height), static_cast<int>(fps), authority);
        return 0;
    } catch (const winrt::hresult_error& error) {
        std::cerr << "Exact window capture unavailable (HRESULT 0x" << std::hex <<
            static_cast<uint32_t>(error.code().value) << ")\n";
        return 72;
    } catch (const std::exception& error) {
        std::cerr << "Exact window capture unavailable: " << error.what() << '\n';
        return 72;
    } catch (...) {
        std::cerr << "Exact window capture unavailable\n";
        return 72;
    }
}
