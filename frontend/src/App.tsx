import { RouterProvider } from 'react-router';
import { router } from './routes/router';

export default function App() {
  return (
    <>
      <div className="preview-badge" role="status">
        PREVIEW · 목업 데이터
      </div>
      <RouterProvider router={router} />
    </>
  );
}
