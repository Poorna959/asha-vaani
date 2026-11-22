import { BrowserRouter, Route, Routes } from "react-router";
import { Layout } from "./components/Layout";
import { ReleaseProvider } from "./hooks/useRelease";
import { DownloadPage } from "./pages/Download";
import { HomePage } from "./pages/Home";
import { NotFoundPage } from "./pages/NotFound";
import { PrivacyPage } from "./pages/Privacy";

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="download" element={<DownloadPage />} />
        <Route path="privacy" element={<PrivacyPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export function App() {
  return (
    <ReleaseProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </ReleaseProvider>
  );
}
